package com.reproio.embulk.input.trino;

import io.trino.jdbc.TrinoDriver;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Properties;
import org.embulk.input.jdbc.AbstractJdbcInputPlugin;
import org.embulk.input.jdbc.JdbcInputConnection;
import org.embulk.util.config.Config;
import org.embulk.util.config.ConfigDefault;

public class TrinoJdbcInputPlugin extends AbstractJdbcInputPlugin {

  @Override
  protected JdbcInputConnection newConnection(PluginTask task) throws SQLException {
    TrinoJdbcPluginTask t = (TrinoJdbcPluginTask) task;
    DriverManager.registerDriver(new TrinoDriver());
    Properties properties = new Properties();
    properties.setProperty("user", t.getUser());
    if (t.getPassword().isPresent()) {
      properties.setProperty("password", t.getPassword().get());
    }
    String url = buildUrl(t);
    Connection connection = DriverManager.getConnection(url, properties);
    return new JdbcInputConnection(connection, null);
  }

  // Build URL such as `jdbc:trino://${host}:${port}/${catalog}/${schema}`
  private String buildUrl(TrinoJdbcPluginTask t) {
    var b = new StringBuilder("jdbc:trino://");
    b.append(t.getHost());
    b.append(":");
    b.append(t.getPort());
    if (t.getCatalog().isPresent()) {
      b.append("/");
      b.append(t.getCatalog().get());
      if (t.getSchema().isPresent()) {
        b.append("/");
        b.append(t.getSchema().get());
      }
    }
    return b.toString();
  }

  @Override
  protected Class<? extends PluginTask> getTaskClass() {
    return TrinoJdbcPluginTask.class;
  }

  // See https://trino.io/docs/current/client/jdbc.html
  interface TrinoJdbcPluginTask extends PluginTask {

    @Config("driver_path")
    @ConfigDefault("null")
    Optional<String> getDriverPath();

    @Config("host")
    @ConfigDefault("\"localhost\"")
    String getHost();

    @Config("port")
    @ConfigDefault("8080")
    int getPort();

    @Config("user")
    @ConfigDefault("\"embulk\"")
    String getUser();

    @Config("password")
    @ConfigDefault("null")
    Optional<String> getPassword();

    @Config("catalog")
    @ConfigDefault("null")
    Optional<String> getCatalog();

    @Config("schema")
    @ConfigDefault("null")
    Optional<String> getSchema();
  }
}
