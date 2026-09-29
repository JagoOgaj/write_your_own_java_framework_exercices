package com.github.forax.framework.orm;

import javax.sql.DataSource;
import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.Serial;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ORM {
  private static final ThreadLocal<Connection> ORM_THREAD_LOCAL = new ThreadLocal<>();
  private ORM() {
    throw new AssertionError();
  }

  @FunctionalInterface
  public interface TransactionBlock {
    void run() throws SQLException;
  }

  private static final Map<Class<?>, String> TYPE_MAPPING = Map.of(
      int.class, "INTEGER",
      Integer.class, "INTEGER",
      long.class, "BIGINT",
      Long.class, "BIGINT",
      String.class, "VARCHAR(255)"
  );

  private static Class<?> findBeanTypeFromRepository(Class<?> repositoryType) {
    var repositorySupertype = Arrays.stream(repositoryType.getGenericInterfaces())
        .flatMap(superInterface -> {
          if (superInterface instanceof ParameterizedType parameterizedType
              && parameterizedType.getRawType() == Repository.class) {
            return Stream.of(parameterizedType);
          }
          return null;
        })
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("invalid repository interface " + repositoryType.getName()));
    var typeArgument = repositorySupertype.getActualTypeArguments()[0];
    if (typeArgument instanceof Class<?> beanType) {
      return beanType;
    }
    throw new IllegalArgumentException("invalid type argument " + typeArgument + " for repository interface " + repositoryType.getName());
  }

  private static class UncheckedSQLException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 42L;

    private UncheckedSQLException(SQLException cause) {
      super(cause);
    }

    @Override
    public SQLException getCause() {
      return (SQLException) super.getCause();
    }
  }


  // --- do not change the code above

  //TODO
  public static void transaction(DataSource dataSource, TransactionBlock block) throws SQLException {
    Objects.requireNonNull(dataSource);
    Objects.requireNonNull(block);
    try(var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      ORM_THREAD_LOCAL.set(connection);
      try {
        block.run();
        connection.commit();
      } catch(RuntimeException | SQLException e) {
        var cause = switch (e) {
          case UncheckedSQLException unchecked -> unchecked.getCause();
          default -> e;
        };
        try {
          connection.rollback();
        } catch(SQLException suppressed) {
          cause.addSuppressed(suppressed);
        }
        throw Utils.rethrow(cause);
      } finally{
        ORM_THREAD_LOCAL.remove();
      }
    }
  }

  static Connection currentConnection() {
    var con = ORM_THREAD_LOCAL.get();
    if (con == null) {
      throw new IllegalStateException();
    }
    return con;
  }

  static String findTableName(Class<?> type) {
    if (type.isAnnotationPresent(Table.class)) {
      return type.getAnnotation(Table.class).value().toUpperCase(Locale.ROOT);
    }
    return type.getSimpleName().toUpperCase(Locale.ROOT);
  }

  static String findColumnName(PropertyDescriptor propertyDescriptor) {
    var annotation = propertyDescriptor.getReadMethod().getAnnotation(Column.class);
    if (annotation != null) {
      return annotation.value().toUpperCase(Locale.ROOT);
    }
    return propertyDescriptor.getName().toUpperCase(Locale.ROOT);
  }

  private static List<PropertyDescriptor> findProperties(Class<?> beanClass, Predicate<? super PropertyDescriptor> predicate) {
    return Arrays.stream(Utils.beanInfo(beanClass).getPropertyDescriptors())
            .filter(predicate)
            .toList();
  }

  private static String sqlColumnBuilder(PropertyDescriptor propertyDescriptor) {
    var type = propertyDescriptor.getPropertyType();
    var needAutoIncrement = propertyDescriptor.getReadMethod().getAnnotation(GeneratedValue.class) != null;
    var needID = propertyDescriptor.getReadMethod().getAnnotation(Id.class) != null;
    var columnName = findColumnName(propertyDescriptor);
    return columnName
            + " "
            + TYPE_MAPPING.getOrDefault(type, "VARCHAR(255)")
            + (type.isPrimitive() ? " NOT NULL" : "")
            + (needAutoIncrement ? " AUTO_INCREMENT" : "")
            + (needID ? ", PRIMARY KEY (" +columnName+ ")" : "");
  }

  public static void createTable(Class<?> beanClass) throws SQLException {
    Objects.requireNonNull(beanClass);
    var con = currentConnection();
    var sqlQuery = findProperties(beanClass, p -> p.getReadMethod() != null && !p.getName().equals("class"))
            .stream()
            .map(ORM::sqlColumnBuilder)
            .collect(Collectors.joining(", ", "CREATE TABLE " + findTableName(beanClass) + " (", ")"));

    try (Statement statement = con.createStatement()) {
      statement.executeUpdate(sqlQuery);
    }
  }

  public static <T> T createRepository(Class<T> repositoryType)  {
    Objects.requireNonNull(repositoryType);
    var beanClass = findBeanTypeFromRepository(repositoryType);
    var beanInfo = Utils.beanInfo(beanClass);
    var constructor = Utils.defaultConstructor(beanClass);
    var tableName = findTableName(beanClass);

    return repositoryType.cast(Proxy.newProxyInstance(
            repositoryType.getClassLoader(),
            new Class<?>[]{repositoryType},
            (_, method, _) -> {
              if (method.getDeclaringClass() == Object.class) {
                throw new UnsupportedOperationException();
              }
              var connection = currentConnection();
              return switch (method.getName()) {
                case "findAll" -> findAll(connection, "SELECT * FROM " + tableName, beanInfo, constructor);
                case "equals", "hashCode", "toString" -> throw new UnsupportedOperationException();
                default -> throw new IllegalStateException();
              };
            }
    ));
  }

  static <T> T toEntityClass(ResultSet resultSet, BeanInfo beanInfo, Constructor<T> constructor) throws SQLException {
    var instance = Utils.newInstance(constructor);
    var properties = Arrays.stream(beanInfo.getPropertyDescriptors())
            .filter(p -> p.getWriteMethod() != null && !p.getName().equals("class"))
            .toList();

    for (var property : properties) {
      var value = resultSet.getObject(findColumnName(property), property.getPropertyType());
      Utils.invokeMethod(instance, property.getWriteMethod(), value);
    }
    return instance;
  }

  static <T> List<T> findAll(Connection connection, String sqlQuery, BeanInfo beanInfo, Constructor<T> constructor) { // <- Plus de 'throws SQLException'
    var list = new ArrayList<T>();
    try (PreparedStatement statement = connection.prepareStatement(sqlQuery)) {
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          list.add(toEntityClass(resultSet, beanInfo, constructor));
        }
      }
    } catch (SQLException e) {
      throw new UncheckedSQLException(e);
    }
    return list;
  }
}
