/* Copyright 2017 Alfa Financial Software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.alfasoftware.morf.jdbc;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.transform;
import static com.google.common.collect.Maps.uniqueIndex;
import static java.util.stream.Collectors.toList;
import static org.alfasoftware.morf.metadata.SchemaUtils.column;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.alfasoftware.morf.metadata.Column;
import org.alfasoftware.morf.metadata.DataType;
import org.alfasoftware.morf.metadata.Index;
import org.alfasoftware.morf.metadata.Schema;
import org.alfasoftware.morf.metadata.SchemaUtils.ColumnBuilder;
import org.alfasoftware.morf.metadata.Table;
import org.alfasoftware.morf.metadata.View;
import org.alfasoftware.morf.sql.SelectStatement;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;

/**
 * Provides meta data based on a database connection.
 *
 * @author Copyright (c) Alfa Financial Software 2010
 */
public class DatabaseMetaDataProvider implements Schema {

  private static final Log log = LogFactory.getLog(DatabaseMetaDataProvider.class);

  // Column numbers for DatabaseMetaData.getColumns() ResultSet
  protected static final int COLUMN_TABLE_NAME = 3;
  protected static final int COLUMN_NAME = 4;
  protected static final int COLUMN_DATA_TYPE = 5;
  protected static final int COLUMN_TYPE_NAME = 6;
  protected static final int COLUMN_SIZE = 7;
  protected static final int COLUMN_DECIMAL_DIGITS = 9;
  protected static final int COLUMN_REMARKS = 12;
  protected static final int COLUMN_IS_NULLABLE = 18;
  protected static final int COLUMN_IS_AUTOINCREMENT = 23;

  // Column numbers for DatabaseMetaData.getTables() ResultSet
  protected static final int TABLE_SCHEM = 2;
  protected static final int TABLE_NAME = 3;
  protected static final int TABLE_TYPE = 4;
  protected static final int TABLE_REMARKS = 5;

  // Column numbers for DatabaseMetaData.getIndexInfo() ResultSet
  protected static final int INDEX_NON_UNIQUE = 4;
  protected static final int INDEX_NAME = 6;
  protected static final int INDEX_COLUMN_NAME = 9;

  // Column numbers for DatabaseMetaData.getPrimaryKeys() ResultSet
  protected static final int PRIMARY_COLUMN_NAME = 4;
  protected static final int PRIMARY_KEY_SEQ = 5;


  protected final Connection connection;
  protected final String schemaName;


  private final Supplier<Map<String, String>> tableNameMappings = Suppliers.memoize(this::tableNameMappings);

  private final Supplier<Map<String, List<ColumnBuilder>>> columnMappings = Suppliers.memoize(this::columnMappings);

  private final Supplier<Map<String, View>> viewMappings = Suppliers.memoize(this::viewMappings);


  /**
   * @param connection The database connection from which meta data should be provided.
   * @param schemaName The name of the schema in which the data is stored. This might be null.
   */
  protected DatabaseMetaDataProvider(Connection connection, String schemaName) {
    super();
    this.connection = connection;
    this.schemaName = schemaName;
  }


  /**
   * Creates a map of all table names,
   * indexed by their upper-case names.
   */
  protected Map<String, String> tableNameMappings() {
    final ImmutableMap.Builder<String, String> tableNameMappings = ImmutableMap.builder();

    try {
      final DatabaseMetaData databaseMetaData = connection.getMetaData();

      try (ResultSet tableResultSet = databaseMetaData.getTables(null, schemaName, null, tableTypesForTables())) {
        while (tableResultSet.next()) {
          String tableName = readTableName(tableResultSet);
          try {
            String tableSchemaName = tableResultSet.getString(TABLE_SCHEM);
            String tableType = tableResultSet.getString(TABLE_TYPE);

            boolean tableIsSystemTable = isSystemTable(tableName);
            boolean tableShouldBeIgnored = shouldIgnoreTable(tableName);

            if (log.isDebugEnabled()) {
              log.debug("Found table [" + tableName + "] of type [" + tableType + "] in schema [" + tableSchemaName + "]"
                  + (tableIsSystemTable ? " - SYSTEM TABLE" : "") + (tableShouldBeIgnored ? " - IGNORED" : ""));
            }

            if (!tableIsSystemTable && !tableShouldBeIgnored) {
              tableNameMappings.put(tableName.toUpperCase(), tableName);
            }
          }
          catch (SQLException e) {
            throw new RuntimeSqlException("Error reading metadata for table ["+tableName+"]", e);
          }
        }

        return tableNameMappings.build();
      }
    }
    catch (SQLException e) {
      throw new RuntimeSqlException(e);
    }
  }


  /**
   * Types for {@link DatabaseMetaData#getTables(String, String, String, String[])}
   * used by {@link #tableNameMappings()}.
   */
  protected String[] tableTypesForTables() {
    return new String[] { "TABLE" };
  }


  /**
   * Retrieves table name from a result set.
   */
  protected String readTableName(ResultSet tableResultSet) throws SQLException {
    return tableResultSet.getString(TABLE_NAME);
  }


  /**
   * Identify whether or not the table is one owned by the system, or owned by
   * our application. The default implementation assumes that all tables we can
   * access in the schema are under our control.
   *
   * @param tableName The table which we are accessing.
   * @return <var>true</var> if the table is owned by the system
   */
  protected boolean isSystemTable(@SuppressWarnings("unused") String tableName) {
    return false;
  }


  /**
   * Identify whether or not the specified table should be ignored in the metadata. This is
   * typically used to filter temporary tables.
   *
   * @param tableName The table which we are accessing.
   * @return <var>true</var> if the table should be ignored, false otherwise.
   */
  protected boolean shouldIgnoreTable(@SuppressWarnings("unused") String tableName) {
    return false;
  }


  /**
   * @see org.alfasoftware.morf.metadata.Schema#isEmptyDatabase()
   */
  @Override
  public boolean isEmptyDatabase() {
    return tableNameMappings.get().isEmpty();
  }


  /**
   * Return a {@link Table} implementation. Note the metadata is read lazily.
   *
   * @see org.alfasoftware.morf.metadata.Schema#getTable(java.lang.String)
   */
  @Override
  public Table getTable(String name) {
    final String adjustedTableName = tableNameMappings.get().get(name.toUpperCase());

    final List<String> primaryKeys = getPrimaryKeys(adjustedTableName);

    if (adjustedTableName == null) {
      throw new IllegalArgumentException("Table [" + name + "] not found.");
    }

    return new Table() {

      private List<Column> columns;
      private List<Index>  indexes;


      /**
       * @see org.alfasoftware.morf.metadata.Table#getName()
       */
      @Override
      public String getName() {
        return adjustedTableName;
      }


      /**
       * @see org.alfasoftware.morf.metadata.Table#columns()
       */
      @Override
      public List<Column> columns() {
        if (columns == null) {
          columns = readColumns(adjustedTableName, primaryKeys);
        }
        return columns;
      }


      /**
       * @see org.alfasoftware.morf.metadata.Table#indexes()
       */
      @Override
      public List<Index> indexes() {
        if (indexes == null) {
          indexes = readIndexes(adjustedTableName);
        }
        return indexes;
      }


      @Override
      public boolean isTemporary() {
        return false;
      }

    };
  }


  /**
   * Finds the primary keys for the {@code tableName}.
   *
   * @param tableName the table to query for.
   * @return a collection of the primary keys.
   */
  protected List<String> getPrimaryKeys(String tableName) {
    List<PrimaryKeyColumn> columns = newArrayList();
    try {
      final DatabaseMetaData databaseMetaData = connection.getMetaData();

      try (ResultSet primaryKeyResults = databaseMetaData.getPrimaryKeys(null, schemaName, tableName)) {
        while (primaryKeyResults.next()) {
          columns.add(new PrimaryKeyColumn(primaryKeyResults.getShort(PRIMARY_KEY_SEQ), primaryKeyResults.getString(PRIMARY_COLUMN_NAME)));
        }
      }
    } catch (SQLException sqle) {
      throw new RuntimeSqlException("Error reading primary keys for table [" + tableName + "]", sqle);
    }

    List<PrimaryKeyColumn> sortedColumns = Ordering.from(new Comparator<PrimaryKeyColumn>() {
      @Override
      public int compare(PrimaryKeyColumn o1, PrimaryKeyColumn o2) {
        return o1.sequence.compareTo(o2.sequence);
      }
    }).sortedCopy(columns);

    // Convert to String before returning
    return transform(sortedColumns, new Function<PrimaryKeyColumn, String>() {
      @Override
      public String apply(PrimaryKeyColumn input) {
        return input.name;
      }
    });
  }


  /**
   * Read out the columns for a particular table.
   *
   * @param tableName The source table
   * @param primaryKeys the primary keys for the table.
   * @return a list of columns
   */
  protected List<Column> readColumns(String tableName, List<String> primaryKeys) {
    List<Column> result = new LinkedList<>();
    List<ColumnBuilder> rawColumns = columnMappings.get().get(tableName);
    for (ColumnBuilder column : rawColumns) {
      result.add(primaryKeys.contains(column.getName()) ? column.primaryKey() : column);
    }
    return applyPrimaryKeyOrder(primaryKeys, result);
  }


  private Map<String, List<ColumnBuilder>> columnMappings() {
    final Map<String, List<ColumnBuilder>> columnMappings = new HashMap<>();
    try {
      try {
        final DatabaseMetaData databaseMetaData = connection.getMetaData();

        try (ResultSet columnResults = databaseMetaData.getColumns(null, schemaName, null, null)) {

          while (columnResults.next()) {
            String tableName = columnResults.getString(COLUMN_TABLE_NAME);
            if (!tableExists(tableName)) {
              continue;
            }

            String columnName = getColumnName(columnResults);

            try {
              String typeName = columnResults.getString(COLUMN_TYPE_NAME);
              int typeCode = columnResults.getInt(COLUMN_DATA_TYPE);
              int width = columnResults.getInt(COLUMN_SIZE);
              DataType dataType = dataTypeFromSqlType(typeCode, typeName, width);
              int scale = columnResults.getInt(COLUMN_DECIMAL_DIGITS);
              boolean nullable = columnResults.getString(COLUMN_IS_NULLABLE).equals("YES");
              String defaultValue = determineDefaultValue(columnName);

              ColumnBuilder column = column(columnName, dataType, width, scale).defaultValue(defaultValue);
              column = nullable ? column.nullable() : column;
              column = setAdditionalColumnMetadata(tableName, column, columnResults);

              columnMappings.computeIfAbsent(tableName, k -> new LinkedList<>()).add(column);

            } catch (Exception e) {
              throw new RuntimeException(
                  String.format("Error reading metadata for column [%s] on table [%s]", columnName, tableName), e);
            }
          }
        }
      } catch (SQLException sqle) {
        throw new RuntimeSqlException(sqle);
      }
    } catch (Exception ex) {
      throw new RuntimeException("Error reading metadata for schema [" + schemaName + "]", ex);
    }
    return columnMappings;
  }

  /**
   * Retrieves column name from a result set.
   */
  protected String getColumnName(ResultSet columnResultSet) throws SQLException {
    return columnResultSet.getString(COLUMN_NAME);
  }


  /**
   * Apply the sort order of the primary keys to the list of columns, but leave non-primary keys intact.
   *
   * @param primaryKeys the sorted primary key column names
   * @param columns all the columns
   * @return the partially sorted columns
   */
  protected List<Column> applyPrimaryKeyOrder(List<String> primaryKeys, List<Column> columns) {
    // Map allowing retrieval of columns by name
    ImmutableMap<String, Column> columnsMap = uniqueIndex(columns, Column::getName);

    List<Column> reorderedColumns = new ArrayList<>();
    // keep track of the primary keys that have been added
    int primaryKeyIndex = 0;
    for (Column column : columns) {
      if (column.isPrimaryKey()) {
        // replace whatever primary key is there with the one that should be at that index
        String pkName = primaryKeys.get(primaryKeyIndex);
        Column primaryKeyColumn = columnsMap.get(pkName);
        if (primaryKeyColumn == null) {
          throw new IllegalStateException("Could not find primary key column [" + pkName + "] in columns [" + columns + "]");
        }
        reorderedColumns.add(primaryKeyColumn);
        primaryKeyIndex++;
      } else {
        reorderedColumns.add(column);
      }
    }

    return reorderedColumns;
  }


  /**
   * Implements the default method for obtaining additional column information. For the default method this is only
   * as to whether a column is autonumbered, and if so, from what start value, from the database.  Optionally overridden in specific RDBMS implementations
   * where the information is available from different sources and additional column information is available.
   *
   * @param tableName The name of the table.
   * @param columnBuilder The column under construction.
   * @param columnMetaData The JDBC column metdata, if required.
   * @return The modified column
   * @throws SQLException when a problem in retrieving information from the database is encountered.
   */
  @SuppressWarnings("unused")
  protected ColumnBuilder setAdditionalColumnMetadata(String tableName, ColumnBuilder columnBuilder, ResultSet columnMetaData) throws SQLException {
    return "YES".equals(columnMetaData.getString(COLUMN_IS_AUTOINCREMENT)) ? columnBuilder.autoNumbered(-1) : columnBuilder;
  }


  /**
   * Read out the indexes for a particular table
   *
   * @param tableName The source table
   * @return The indexes for the table
   */
  protected List<Index> readIndexes(String tableName) {
    try {
      final DatabaseMetaData databaseMetaData = connection.getMetaData();

      try (ResultSet indexResultSet = databaseMetaData.getIndexInfo(null, schemaName, tableName, false, false)) {
        List<Index> indexes = new LinkedList<>();

        SortedMap<String, List<String>> columnsByIndexName = new TreeMap<>();

        // there's one entry for each column in the index
        // the results are sorted by ordinal position already
        while (indexResultSet.next()) {
          String indexName = getIndexName(indexResultSet);

          if (indexName == null) {
            continue;
          }

          // don't output the primary key as an index
          if (isPrimaryKeyIndex(indexName)) {
            continue;
          }

          // some indexes should be ignored anyway
          if (DatabaseMetaDataProviderUtils.shouldIgnoreIndex(indexName)) {
            continue;
          }

          String columnName = indexResultSet.getString(INDEX_COLUMN_NAME);

          List<String> columnNames = columnsByIndexName.get(indexName);
          // maybe create a new one
          if (columnNames == null) {
            columnNames = new LinkedList<>();
            boolean unique = !indexResultSet.getBoolean(INDEX_NON_UNIQUE);

            indexes.add(new IndexImpl(indexName, unique, columnNames));
            columnsByIndexName.put(indexName, columnNames);
          }

          // add this column to the list
          columnNames.add(columnName);
        }
        return indexes;
      }
    } catch (SQLException sqle) {
      throw new RuntimeSqlException("Error reading metadata for table [" + tableName + "]", sqle);
    }
  }


  /**
   * Retrieves index name from a result set.
   */
  protected String getIndexName(ResultSet indexResultSet) throws SQLException {
    return indexResultSet.getString(INDEX_NAME);
  }


  private Map<String, View> viewMappings() {
    final ImmutableMap.Builder<String, View> viewMappings = ImmutableMap.builder();
    try {
      final DatabaseMetaData databaseMetaData = connection.getMetaData();

      try (ResultSet views = databaseMetaData.getTables(null, schemaName, null, getTableTypesForViews())) {
        while (views.next()) {
          final String viewName = getViewName(views);
          log.debug("Found view [" + viewName + "]");
          viewMappings.put(viewName.toUpperCase(), new View() {
            @Override
            public String getName() {
              return viewName;
            }

            @Override
            public boolean knowsSelectStatement() {
              return false;
            }

            @Override
            public boolean knowsDependencies() {
              return false;
            }

            @Override
            public SelectStatement getSelectStatement() {
              throw new UnsupportedOperationException("Cannot return SelectStatement as [" + viewName + "] has been loaded from the database");
            }

            @Override
            public String[] getDependencies() {
              throw new UnsupportedOperationException("Cannot return dependencies as [" + viewName + "] has been loaded from the database");
            }
          });
        }

        return viewMappings.build();
      }
    } catch (SQLException sqle) {
      throw new RuntimeSqlException("Error reading metadata for views", sqle);
    }
  }


  /**
   * Types for {@link DatabaseMetaData#getTables(String, String, String, String[])}
   * used by {@link #populateViewMap()}.
   */
  protected String[] getTableTypesForViews() {
    return new String[] { "VIEW" };
  }


  /**
   * Retrieves view name from a result set.
   */
  protected String getViewName(ResultSet viewResultSet) throws SQLException {
    return viewResultSet.getString(TABLE_NAME);
  }


  /**
   * @param indexName The index name
   * @return Whether this is the primary key for this table
   */
  protected boolean isPrimaryKeyIndex(String indexName) {
    return "PRIMARY".equals(indexName);
  }


  /**
   * Converts a given SQL data type to a {@link DataType}.
   *
   * @param sqlType The jdbc data type to convert.
   * @param typeName The (potentially database specific) type name
   * @param width The width of the column
   * @return The Cryo DataType that represents the sql connection data type
   *         given in <var>sqlType</var>.
   */
  protected DataType dataTypeFromSqlType(int sqlType, String typeName, @SuppressWarnings("unused") int width) {
    switch (sqlType) {
      case Types.TINYINT:
      case Types.SMALLINT:
      case Types.INTEGER:
        return DataType.INTEGER;
      case Types.BIGINT:
        return DataType.BIG_INTEGER;
      case Types.FLOAT:
      case Types.REAL:
      case Types.DOUBLE:
      case Types.NUMERIC:
      case Types.DECIMAL:
        return DataType.DECIMAL;
      case Types.CHAR:
      case Types.VARCHAR:
      case Types.LONGVARCHAR:
      case Types.LONGNVARCHAR:
      case Types.NVARCHAR:
        return DataType.STRING;
      case Types.BOOLEAN:
      case Types.BIT:
        return DataType.BOOLEAN;
      case Types.DATE:
        return DataType.DATE;
      case Types.BLOB:
      case Types.BINARY:
      case Types.VARBINARY:
      case Types.LONGVARBINARY:
        return DataType.BLOB;
      case Types.NCLOB:
      case Types.CLOB:
        return DataType.CLOB;

      default:
        throw new IllegalArgumentException("Unknown SQL data type [" + typeName + "] (" + sqlType + ")");
    }
  }


  /**
   * @see org.alfasoftware.morf.metadata.Schema#tableExists(java.lang.String)
   */
  @Override
  public boolean tableExists(String name) {
    return tableNameMappings.get().containsKey(name.toUpperCase());
  }


  /**
   * @see org.alfasoftware.morf.metadata.Schema#tableNames()
   */
  @Override
  public Collection<String> tableNames() {
    if (log.isDebugEnabled()) {
      log.debug("Find tables in schema [" + schemaName + "]");
    }

    return tableNameMappings.get().values();
  }


  /**
   * @see org.alfasoftware.morf.metadata.Schema#tables()
   */
  @Override
  public Collection<Table> tables() {
    List<Table> result = new ArrayList<>();
    for (String tableName : tableNames()) {
      result.add(getTable(tableName));
    }
    return result;
  }


  /**
   * Sets the default value to an empty string for any column other than
   * version. Database-schema level default values are not supported by ALFA's
   * domain model hence we don't want to include a default value in the xml
   * definition of a table.
   *
   * @param columnName the name of the column
   * @return the default value
   */
  protected String determineDefaultValue(String columnName) {
    if (columnName.equals("version") || columnName.equals("version".toUpperCase())) {
      return "0";
    }

    return "";
  }

  /**
   * Implementation of {@link Index} for database meta data
   */
  protected static final class IndexImpl implements Index {

    /**
     * index name
     */
    private final String       name;
    /**
     * index is unique
     */
    private final boolean      isUnique;
    /**
     * index columns
     */
    private final List<String> columnNames;


    /**
     * @param name The name of the index
     * @param isUnique Whether the index is unique
     * @param columnNames The index column names
     */
    public IndexImpl(String name, boolean isUnique, List<String> columnNames) {
      super();
      this.name = name;
      this.isUnique = isUnique;
      this.columnNames = columnNames;
    }


    /**
     * @see org.alfasoftware.morf.metadata.Index#columnNames()
     */
    @Override
    public List<String> columnNames() {
      return columnNames;
    }


    /**
     * @see org.alfasoftware.morf.metadata.Index#getName()
     */
    @Override
    public String getName() {
      return name;
    }


    /**
     * @see org.alfasoftware.morf.metadata.Index#isUnique()
     */
    @Override
    public boolean isUnique() {
      return isUnique;
    }


    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
      return this.toStringHelper();
    }
  }

  /**
   * @see org.alfasoftware.morf.metadata.Schema#viewExists(java.lang.String)
   */
  @Override
  public boolean viewExists(String name) {
    return viewMappings.get().containsKey(name.toUpperCase());
  }


  /**
   * @see org.alfasoftware.morf.metadata.Schema#getView(java.lang.String)
   */
  @Override
  public View getView(String name) {
    return viewMappings.get().get(name.toUpperCase());
  }


  /**
   * @see org.alfasoftware.morf.metadata.Schema#viewNames()
   */
  @Override
  public Collection<String> viewNames() {
    return viewMappings.get().values().stream().map(View::getName).collect(toList());
  }


  /**
   * @see org.alfasoftware.morf.metadata.Schema#views()
   */
  @Override
  public Collection<View> views() {
    return viewMappings.get().values();
  }

  private static class PrimaryKeyColumn {
    final Short sequence;
    final String name;
    PrimaryKeyColumn(Short sequence, String name) {
      this.sequence = sequence;
      this.name = name;
    }
  }
}
