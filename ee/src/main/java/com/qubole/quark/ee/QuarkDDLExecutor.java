/*
 * Copyright (c) 2015. Qubole Inc
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.qubole.quark.ee;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlNumericLiteral;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.pretty.SqlPrettyWriter;

import com.qubole.quark.QuarkException;
import com.qubole.quark.catalog.db.Connection;
import com.qubole.quark.catalog.db.dao.DataSourceDAO;
import com.qubole.quark.catalog.db.dao.JdbcSourceDAO;
import com.qubole.quark.catalog.db.dao.QuboleDbSourceDAO;
import com.qubole.quark.catalog.db.dao.ViewDAO;
import com.qubole.quark.catalog.db.encryption.AESEncrypt;
import com.qubole.quark.catalog.db.encryption.Encrypt;
import com.qubole.quark.catalog.db.encryption.NoopEncrypt;
import com.qubole.quark.catalog.db.pojo.DataSource;
import com.qubole.quark.catalog.db.pojo.JdbcSource;
import com.qubole.quark.catalog.db.pojo.QuboleDbSource;
import com.qubole.quark.catalog.db.pojo.View;
import com.qubole.quark.planner.parser.ParserFactory;
import com.qubole.quark.planner.parser.ParserResult;

import com.qubole.quark.planner.parser.QuarkParserImpl;
import com.qubole.quark.planner.parser.sql.SqlAlterQuarkDataSource;
import com.qubole.quark.planner.parser.sql.SqlAlterQuarkView;
import com.qubole.quark.planner.parser.sql.SqlCreateQuarkDataSource;
import com.qubole.quark.planner.parser.sql.SqlCreateQuarkView;
import com.qubole.quark.planner.parser.sql.SqlDropQuarkDataSource;
import com.qubole.quark.planner.parser.sql.SqlDropQuarkView;
import com.qubole.quark.planner.parser.sql.SqlShowDataSources;
import com.qubole.quark.planner.parser.sql.SqlShowViews;

import org.skife.jdbi.v2.DBI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Created by adeshr on 5/24/16.
 */
public class QuarkDDLExecutor implements QuarkExecutor {
  private static final Logger LOG = LoggerFactory.getLogger(QuarkDDLExecutor.class);

  private Properties info;
  private final Connection connection;
  private ParserFactory parserFactory;

  public QuarkDDLExecutor(ParserFactory parserFactory, Properties info) {
    this.parserFactory = parserFactory;
    this.info = info;
    this.connection = new Connection(info);
    this.connection.getDSSet();
  }

  private DBI getDbi() throws SQLException {
    try {
      return this.connection.getDbi();
    } catch (QuarkException e) {
      throw new SQLException(e);
    }
  }
  public Object execute(ParserResult result) throws SQLException {
    SqlParser parser = SqlParser.create(result.getParsedSql(),
        SqlParser.configBuilder()
            .setQuotedCasing(Casing.UNCHANGED)
            .setUnquotedCasing(Casing.UNCHANGED)
            .setQuoting(Quoting.DOUBLE_QUOTE)
            .setParserFactory(QuarkParserImpl.FACTORY)
            .build());
    SqlNode sqlNode;
    try {
      sqlNode = parser.parseStmt();
    } catch (SqlParseException e) {
      throw new RuntimeException(
          "parse failed: " + e.getMessage(), e);
    }
    if (sqlNode instanceof SqlCreateQuarkDataSource) {
      int id = executeCreateDataSource((SqlCreateQuarkDataSource) sqlNode);
      parserFactory.setReloadCache();
      return id;
    } else if (sqlNode instanceof SqlAlterQuarkDataSource) {
      int id = executeAlterDataSource((SqlAlterQuarkDataSource) sqlNode);
      parserFactory.setReloadCache();
      return id;
    } else if (sqlNode instanceof SqlDropQuarkDataSource) {
      executeDeleteOnDataSource((SqlDropQuarkDataSource) sqlNode);
      parserFactory.setReloadCache();
      return 0;
    } else if (sqlNode instanceof SqlCreateQuarkView) {
      int id = executeCreateView((SqlCreateQuarkView) sqlNode);
      parserFactory.setReloadCache();
      return id;
    } else if (sqlNode instanceof SqlAlterQuarkView) {
      int id = executeAlterView((SqlAlterQuarkView) sqlNode);
      parserFactory.setReloadCache();
      return id;
    } else if (sqlNode instanceof SqlDropQuarkView) {
      executeDeleteOnView((SqlDropQuarkView) sqlNode);
      parserFactory.setReloadCache();
      return 0;
    } else if (sqlNode instanceof SqlShowDataSources) {
      return getDataSourceList((SqlShowDataSources) sqlNode);
    } else if (sqlNode instanceof SqlShowViews) {
      return getViewList((SqlShowViews) sqlNode);
    }
    throw new RuntimeException("Cannot handle execution for: " + result.getParsedSql());
  }

  public int executeAlterDataSource(SqlAlterQuarkDataSource sqlNode) throws SQLException {
    DBI dbi = getDbi();
    DataSourceDAO dataSourceDAO = dbi.onDemand(DataSourceDAO.class);
    JdbcSourceDAO jdbcDAO = dbi.onDemand(JdbcSourceDAO.class);
    QuboleDbSourceDAO quboleDAO = dbi.onDemand(QuboleDbSourceDAO.class);
    DataSource dataSource = jdbcDAO.findByName(sqlNode.getIdentifier().getSimple(),
        connection.getDSSet().getId());
    if (dataSource == null) {
      dataSource = quboleDAO.findByName(sqlNode.getIdentifier().getSimple(),
          connection.getDSSet().getId());
    }
    if (dataSource == null) {
      return 0;
    }
    SqlNodeList rowList = sqlNode.getSourceExpressionList();
    int i = 0;
    for (SqlNode node : sqlNode.getTargetColumnList()) {
      if (node instanceof SqlIdentifier) {
        switch (((SqlIdentifier) node).getSimple()) {
          case "name":
            dataSource.setName(rowList.get(i).toString());
            break;
          case "type":
            dataSource.setType(rowList.get(i).toString());
            break;
          case "url":
            dataSource.setUrl(rowList.get(i).toString());
            break;
          case "ds_set_id":
            break;
          case "datasource_type":
            dataSource.setDatasourceType(rowList.get(i).toString());
            break;
          case "username":
            if (dataSource instanceof JdbcSource) {
              ((JdbcSource) dataSource)
                  .setUsername(rowList.get(i).toString());
            }
            break;
          case "password":
            if (dataSource instanceof JdbcSource) {
              ((JdbcSource) dataSource)
                  .setPassword(rowList.get(i).toString());
            }
            break;
          case "dbtap_id":
            if (dataSource instanceof QuboleDbSource) {
              if (rowList.get(i) instanceof SqlNumericLiteral) {
                ((QuboleDbSource) dataSource).setDbTapId(
                    ((SqlNumericLiteral) rowList.get(i)).intValue(true));
              } else {
                throw new SQLException("Incorrect argument type to variable"
                    + " 'dbtap_id'");
              }
            }
            break;
          case "auth_token":
            if (dataSource instanceof QuboleDbSource) {
              ((QuboleDbSource) dataSource)
                  .setAuthToken(rowList.get(i).toString());
            }
            break;
          default:
            throw new SQLException("Unknown parameter: " + ((SqlIdentifier) node).getSimple());
        }
        i++;
      }
    }

    Encrypt encrypt;
    if (Boolean.parseBoolean(info.getProperty("encrypt", "false"))) {
      encrypt = new AESEncrypt(info.getProperty("encryptionKey"));
    } else {
      encrypt = new NoopEncrypt();
    }
    if (dataSource instanceof JdbcSource) {
      return jdbcDAO.update((JdbcSource) dataSource, dataSourceDAO, encrypt);
    } else {
      return quboleDAO.update((QuboleDbSource) dataSource, dataSourceDAO, encrypt);
    }
  }

  private int executeCreateDataSource(SqlCreateQuarkDataSource sqlNode) throws SQLException {
    DBI dbi = getDbi();

    Map<String, Object> commonColumns = new HashMap<>();
    Map<String, Object> dbSpecificColumns = new HashMap<>();
    DataSourceDAO dataSourceDAO = dbi.onDemand(DataSourceDAO.class);
    JdbcSourceDAO jdbcSourceDAO = null;
    QuboleDbSourceDAO quboleDbSourceDAO = null;

    int i = 0;
    SqlNodeList rowList = sqlNode.getSourceExpressionList();
    for (SqlNode node : sqlNode.getTargetColumnList()) {
      if (node instanceof SqlIdentifier) {
        switch (((SqlIdentifier) node).getSimple()) {
          case "type":
            commonColumns.put("type", rowList.get(i).toString());
            break;
          case "url":
            commonColumns.put("url", rowList.get(i).toString());
            break;
          case "ds_set_id":
            break;
          case "datasource_type":
            if (rowList.get(i).toString().toUpperCase().equals("JDBC")) {
              jdbcSourceDAO = dbi.onDemand(JdbcSourceDAO.class);
            } else if (rowList.get(i).toString().toUpperCase().equals("QUBOLEDB")) {
              quboleDbSourceDAO = dbi.onDemand(QuboleDbSourceDAO.class);
            } else {
              throw new SQLException("Incorrect argument type <" + rowList.get(i).toString()
                  + "> to variable 'datasource_type'");
            }
            commonColumns.put("datasource_type", rowList.get(i).toString());
            break;
          case "username":
            dbSpecificColumns.put("username", rowList.get(i).toString());
            break;
          case "password":
            dbSpecificColumns.put("password", rowList.get(i).toString());
            break;
          case "dbtap_id":
            if (rowList.get(i) instanceof SqlNumericLiteral) {
              dbSpecificColumns.put("dbtap_id",
                  ((SqlNumericLiteral) rowList.get(i)).intValue(true));
            } else {
              throw new SQLException("Incorrect argument type to variable"
                  + " 'dbtap_id'");
            }
            break;
          case "auth_token":
            dbSpecificColumns.put("auth_token", rowList.get(i).toString());
            break;
          default:
            throw new SQLException("Unknown parameter: " + ((SqlIdentifier) node).getSimple());
        }
        i++;
      }
    }

    Encrypt encrypt;
    if (Boolean.parseBoolean(info.getProperty("encrypt", "false"))) {
      encrypt = new AESEncrypt(info.getProperty("encryptionKey"));
    } else {
      encrypt = new NoopEncrypt();
    }

    if ((jdbcSourceDAO == null && quboleDbSourceDAO == null)
        || (jdbcSourceDAO != null && quboleDbSourceDAO != null)) {
      throw new RuntimeException("Need to pass exact values to create"
          + " data source of type jdbc or quboleDb");
    } else if (jdbcSourceDAO != null) {
      return dataSourceDAO.insertJDBC((String) sqlNode.getIdentifier().getSimple(),
          (String) commonColumns.get("type"),
          (String) commonColumns.get("url"),
          connection.getDSSet().getId(),
          (String) commonColumns.get("datasource_type"),
          jdbcSourceDAO,
          (String) dbSpecificColumns.get("username"),
          (dbSpecificColumns.get("password") == null) ? ""
              : (String) dbSpecificColumns.get("password"),
          encrypt);
    } else {
      return dataSourceDAO.insertQuboleDB((String) sqlNode.getIdentifier().getSimple(),
          (String) commonColumns.get("type"),
          (String) commonColumns.get("url"),
          connection.getDSSet().getId(),
          (String) commonColumns.get("datasource_type"),
          quboleDbSourceDAO,
          (int) dbSpecificColumns.get("dbtap_id"),
          (String) dbSpecificColumns.get("auth_token"),
          encrypt);
    }
  }

  private void executeDeleteOnDataSource(SqlDropQuarkDataSource node) throws SQLException {
    DBI dbi = getDbi();
    DataSourceDAO dataSourceDAO = dbi.onDemand(DataSourceDAO.class);
    JdbcSourceDAO jdbcDao = dbi.onDemand(JdbcSourceDAO.class);
    QuboleDbSourceDAO quboleDao = dbi.onDemand(QuboleDbSourceDAO.class);
    DataSource jdbcSource = jdbcDao.findByName(node.getIdentifier().getSimple(),
        connection.getDSSet().getId());
    if (jdbcSource != null) {
      jdbcDao.delete(jdbcSource.getId());
      dataSourceDAO.delete(jdbcSource.getId());
    } else {
      DataSource quboleSource = quboleDao.findByName(node.getIdentifier().getSimple(),
          connection.getDSSet().getId());
      if (quboleSource != null) {
        jdbcDao.delete(quboleSource.getId());
        dataSourceDAO.delete(quboleSource.getId());
      }
    }
  }

  public int executeAlterView(SqlAlterQuarkView sqlNode) throws SQLException {
    DBI dbi = getDbi();
    ViewDAO viewDAO = dbi.onDemand(ViewDAO.class);

    View view = viewDAO.findByName(sqlNode.getIdentifier().getSimple(),
        connection.getDSSet().getId());
    if (view == null) {
      return 0;
    }

    SqlNodeList rowList = sqlNode.getSourceExpressionList();
    int i = 0;
    for (SqlNode node : sqlNode.getTargetColumnList()) {
      if (node instanceof SqlIdentifier) {
        switch (((SqlIdentifier) node).getSimple()) {
          case "name":
            view.setName(rowList.get(i).toString());
            break;
          case "description":
            view.setDescription(rowList.get(i).toString());
            break;
          case "query":
            view.setQuery(rowList.get(i).toString());
            break;
          case "schema_name":
            view.setSchema(rowList.get(i).toString());
            break;
          case "table_name":
            view.setTable(rowList.get(i).toString());
            break;
          case "ds_set_id":
            if (rowList.get(i) instanceof SqlNumericLiteral) {
              view.setDsSetId(((SqlNumericLiteral) rowList.get(i)).longValue(true));
            } else {
              throw new SQLException("Incorrect argument type to variable 'ds_set_id'");
            }
            break;
          case "cost":
            if (rowList.get(i) instanceof SqlNumericLiteral) {
              view.setCost(((SqlNumericLiteral) rowList.get(i)).longValue(true));
            } else {
              throw new SQLException("Incorrect argument type to variable 'cost'");
            }
            break;
          case "destination_id":
            if (rowList.get(i) instanceof SqlNumericLiteral) {
              view.setDestinationId(((SqlNumericLiteral) rowList.get(i)).longValue(true));
            } else {
              throw new SQLException("Incorrect argument type to variable 'destination_id'");
            }
            break;
          default:
            throw new SQLException("Unknown parameter: " + ((SqlIdentifier) node).getSimple());
        }
        i++;
      }
    }

    return viewDAO.update(view, connection.getDSSet().getId());
  }

  public int executeCreateView(SqlCreateQuarkView sqlNode) throws SQLException {
    DBI dbi = getDbi();
    List<String> tableNameList = sqlNode.getTableName().names;
    String dataSourceName = tableNameList.get(0);

    ViewDAO viewDAO = dbi.onDemand(ViewDAO.class);

    JdbcSourceDAO jdbcDAO = dbi.onDemand(JdbcSourceDAO.class);
    QuboleDbSourceDAO quboleDAO = dbi.onDemand(QuboleDbSourceDAO.class);
    DataSource dataSource = jdbcDAO.findByName(dataSourceName,
        connection.getDSSet().getId());
    if (dataSource == null) {
      dataSource = quboleDAO.findByName(dataSourceName, connection.getDSSet().getId());
    }

    if (dataSource == null) {
      throw new SQLException("DataSource with name '" + dataSourceName + "' not found");
    }

    SqlPrettyWriter writer = new SqlPrettyWriter(SqlDialect.CALCITE);
    writer.setAlwaysUseParentheses(false);
    writer.setSelectListItemsOnSeparateLines(false);
    writer.setIndentation(0);
    writer.setQuoteAllIdentifiers(true);
    sqlNode.getQuery().unparse(writer, 0, 0);
    final String sql = writer.toString();

    LOG.debug(sql);
    return viewDAO.insert(sqlNode.getName(),
        "No Description", sql,
        0L, dataSource.getId(),
        tableNameList.get(1), tableNameList.get(2),
        connection.getDSSet().getId());
  }

  private void executeDeleteOnView(SqlDropQuarkView node) throws SQLException {
    DBI dbi = getDbi();
    ViewDAO viewDAO = dbi.onDemand(ViewDAO.class);
    viewDAO.delete(node.getIdentifier().getSimple(), connection.getDSSet().getId());
  }

  private List<DataSource> getDataSourceList(SqlShowDataSources sqlNode) throws SQLException {
    DBI dbi = getDbi();
    JdbcSourceDAO jdbcSourceDAO = dbi.onDemand(JdbcSourceDAO.class);
    QuboleDbSourceDAO quboleDbSourceDAO = dbi.onDemand(QuboleDbSourceDAO.class);

    List<DataSource> dataSources = new ArrayList<>();

    if (sqlNode.getLikePattern() == null) {
      dataSources.addAll(jdbcSourceDAO.findByDSSetId(connection.getDSSet().getId()));
      dataSources.addAll(quboleDbSourceDAO.findByDSSetId(connection.getDSSet().getId()));
    } else {
      dataSources.addAll(jdbcSourceDAO.findLikeName(sqlNode.getLikePattern(),
          connection.getDSSet().getId()));
      dataSources.addAll(quboleDbSourceDAO.findLikeName(sqlNode.getLikePattern(),
          connection.getDSSet().getId()));
    }
    return dataSources;
  }

  private List<View> getViewList(SqlShowViews sqlNode) throws SQLException {
    DBI dbi = getDbi();
    ViewDAO viewDAO = dbi.onDemand(ViewDAO.class);

    if (sqlNode.getLikePattern() == null) {
      return viewDAO.findByDSSetId(connection.getDSSet().getId());
    } else {
      return viewDAO.findLikeName(sqlNode.getLikePattern(),
          connection.getDSSet().getId());
    }
  }
}
