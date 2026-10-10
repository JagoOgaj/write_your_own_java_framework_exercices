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
    var idProperty = findId(beanInfo);
    return repositoryType.cast(Proxy.newProxyInstance(
            repositoryType.getClassLoader(),
            new Class<?>[]{repositoryType},
            (proxy, method, args) -> {
              if (method.getDeclaringClass() == Object.class) {
                throw new UnsupportedOperationException();
              }
              var connection = currentConnection();
              var methodName = method.getName();
              return switch (methodName) {
                case "findAll" -> {
                  var sqlQuery = "SELECT * FROM " + tableName;
                  yield findAll(connection, sqlQuery, beanInfo, constructor);
                }
                case "findById" -> {
                  var sqlQuery = "SELECT * FROM " + tableName + " WHERE " + idProperty.getName() + " = ?";
                  yield findAll(connection, sqlQuery, beanInfo, constructor, args[0]).stream().findFirst();
                }
                case "save" -> save(connection, tableName, beanInfo, args[0], idProperty);
                case "equals", "hashCode", "toString" -> throw new UnsupportedOperationException("" + method);
                default -> {
                  var query = method.getAnnotation(Query.class);
                  if (query != null) {
                    yield findAll(connection, query.value(), beanInfo, constructor, args);
                  }
                  if (methodName.startsWith("findBy")) {
                    var propertyName = Introspector.decapitalize(methodName.substring(6));
                    var property = findProperty(beanInfo, propertyName);
                    var sqlQuery = "SELECT * FROM " + tableName + " WHERE " + property.getName() + " = ?";
                    yield findAll(connection, sqlQuery, beanInfo, constructor, args[0]).stream().findFirst();
                  }
                  throw new IllegalStateException("unknown method " + method);
                }
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
      var value = resultSet.getObject(findColumnName(property));
      Utils.invokeMethod(instance, property.getWriteMethod(), value);
    }
    return instance;
  }

  static List<Object> findAll(Connection connection, String sqlQuery, BeanInfo beanInfo, Constructor<?> constructor, Object... args) {
    var list = new ArrayList<>();
    try(var statement = connection.prepareStatement(sqlQuery)) {
      if (args != null) {
        for (var i = 0; i < args.length; i++) {
          statement.setObject(i + 1, args[i]);
        }
      }
      try(var resultSet = statement.executeQuery()) {
        while(resultSet.next()) {
          var instance = toEntityClass(resultSet, beanInfo, constructor);
          list.add(instance);
        }
      }
    }
    catch (SQLException e) {
      throw new UncheckedSQLException(e);
    }
    return list;
  }

  static String createSaveQuery(String tableName, BeanInfo beanInfo) {
    var properties = Arrays.stream(beanInfo.getPropertyDescriptors())
                    .filter(p -> p.getReadMethod() != null && !p.getName().equals("class"))
                    .toList();
    var columns = properties.stream()
                  .map(ORM::findColumnName)
                  .collect(Collectors.joining(", "));
    var questionMarks = properties.stream()
                        .map(_ -> "?")
                        .collect(Collectors.joining(", "));

    return "MERGE INTO " + tableName + " (" + columns + ") VALUES (" + questionMarks + ");";
  }

  static Object save(Connection connection, String tableName, BeanInfo beanInfo, Object bean, PropertyDescriptor idProperty) {
    String sqlQuery = createSaveQuery(tableName, beanInfo);

    try(var statement = connection.prepareStatement(sqlQuery, Statement.RETURN_GENERATED_KEYS)) {
      var index = 1;
      for(var property: beanInfo.getPropertyDescriptors()) {
        if (property.getName().equals("class")) {
          continue;
        }
        statement.setObject(index++, Utils.invokeMethod(bean, property.getReadMethod()));
      }
      statement.executeUpdate();
      if (idProperty != null) {
        try(var resultSet = statement.getGeneratedKeys()) {
          if (resultSet.next()) {
            var key = resultSet.getObject(1);
            Utils.invokeMethod(bean, idProperty.getWriteMethod(), key);
          }
        }
      }
    }
    catch (SQLException e) {
      throw new UncheckedSQLException(e);
    }
    return bean;
  }

  static PropertyDescriptor findId(BeanInfo beanInfo) {
    return Arrays.stream(beanInfo.getPropertyDescriptors())
            .filter(p -> p.getReadMethod() != null && p.getReadMethod().isAnnotationPresent(Id.class))
            .findFirst()
            .orElse(null);
  }

  static PropertyDescriptor findProperty(BeanInfo beanInfo, String propertyName) {
    return Arrays.stream(beanInfo.getPropertyDescriptors())
            .filter(property -> property.getName().equals(propertyName))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no property " + propertyName + " found"));
  }
}
