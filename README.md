# Trino input plugin for Embulk

Trino input plugin for Embulk loads records from Trino.

## Overview

* **Plugin type**: input
* **Resume supported**: yes

## How to install

```
java -jar /path/to/embulk.jar install com.reproio.embulk:embulk-input-trino-jdbc:<version>
```

## Configuration

- **host**: database host name (string, required)
- **port**: database port number (integer, default: 8080)
- **user**: database login user name (string, required)
- **password**: database login password (string, default: "")
- **catalog**: database catalog (string, default: "")
- **schema**: database schema (string, default: "")
- If you write SQL directly,
  - **query**: SQL to run (string)
  - **use_raw_query_with_incremental**: If true, you can write optimized query using prepared statement by yourself. See [Use incremental loading with raw query](#use-incremental-loading-with-raw-query) for more detail (boolean, default: false)
- If **query** is not set,
  - **table**: destination table name (string, required)
  - **select**: expression of select (e.g. `id, created_at`) (string, default: "*")
  - **where**: WHERE condition to filter the rows (string, default: no-condition)
  - **order_by**: expression of ORDER BY to sort rows (e.g. `created_at DESC, id ASC`) (string, default: not sorted)
- **fetch_rows**: number of rows to fetch one time (integer, default: 10000)
  - If this value is set to > 1:
    - It uses a server-side prepared statement and fetches rows by chunks.
    - Internally, `useCursorFetch=true` is enabled and `java.sql.Statement.setFetchSize` is set to the configured value.
  - If this value is set to 1:
    - It uses a client-side built statement and fetches rows one by one.
    - Internally, `useCursorFetch=false` is used and `java.sql.Statement.setFetchSize` is set to Integer.MIN_VALUE.
  - If this value is set to -1:
    - It uses a client-side built statement and fetches all rows at once. This may cause OutOfMemoryError.
    - Internally, `useCursorFetch=false` is used and `java.sql.Statement.setFetchSize` is not set.
- **connect_timeout**: timeout for socket connect. 0 means no timeout. (integer (seconds), default: 300)
- **socket_timeout**: timeout on network socket operations. 0 means no timeout. (integer (seconds), default: 1800)
- **ssl**: **Not implemented**. ~~use SSL to connect to the database (string, default: `disable`. `enable` uses SSL without server-side validation nor verify checks the certificate. For compatibility reasons, `true` behaves as `enable` and `false` behaves as `disable`.)~~
- **options**: extra JDBC properties (hash, default: {})
- **incremental**: if true, enables incremental loading. See next section for details (boolean, default: false)
- **incremental_columns**: column names for incremental loading (array of strings, default: use primary keys). Columns of integer types, string types, `datetime` and `timestamp` are supported.
- **last_record**: values of the last record for incremental loading (array of objects, default: load all records)
- **default_timezone**: If the sql type of a column is `date`/`time`/`datetime` and the embulk type is `string`, column values are formatted int this default_timezone. You can overwrite timezone for each columns using column_options option. (string, default: `UTC`)
- **default_column_options**: advanced: column_options for each JDBC type as default. key-value pairs where key is a JDBC type (e.g. 'DATE', 'BIGINT') and value is same as column_options's value.
- **column_options**: advanced: key-value pairs where key is a column name and value is options for the column.
  - **value_type**: embulk get values from database as this value_type. Typically, the value_type determines `getXXX` method of `java.sql.PreparedStatement`.
  (string, default: depends on the sql type of the column. Available values options are: `long`, `double`, `float`, `decimal`, `boolean`, `string`, `json`, `date`, `time`, `timestamp`)
  - **type**: Column values are converted to this embulk type.
  Available values options are: `boolean`, `long`, `double`, `string`, `json`, `timestamp`).
  By default, the embulk type is determined according to the sql type of the column (or value_type if specified).
  - **timestamp_format**: If the sql type of the column is `date`/`time`/`datetime` and the embulk type is `string`, column values are formatted by this timestamp_format. And if the embulk type is `timestamp`, this timestamp_format may be used in the output plugin. For example, stdout plugin use the timestamp_format, but *csv formatter plugin doesn't use*. (string, default : `%Y-%m-%d` for `date`, `%H:%M:%S` for `time`, `%Y-%m-%d %H:%M:%S` for `timestamp`)
  - **timezone**: If the sql type of the column is `date`/`time`/`datetime` and the embulk type is `string`, column values are formatted in this timezone.
(string, value of default_timezone option is used by default)
- **before_setup**: if set, this SQL will be executed before setup. You can prepare table for input by this option.
- **before_select**: if set, this SQL will be executed before the SELECT query in the same transaction.
- **after_select**: if set, this SQL will be executed after the SELECT query in the same transaction.

## Incremental loading

See sub projects of [embulk-input-jdbc](https://github.com/embulk/embulk-input-jdbc/tree/master) such as embulk-input-mysql.

## Build

```console
$ ./gradlew build
```

## Release

```console
$ ./gradlew publish
```

## Local Development

Copy Maven dependencies to `$HOME/.m2/repository`.

```
$ ./gradlew cacheToMavenLocal
$ ./gradlew publishToMavenLocal
```

Create `$HOME/.embulk/embulk.properties`.

```
m2_repo=<path to .m2/repository>
jruby=file://<path to jruby.jar>
plugins.input.trino-jdbc=maven:com.reproio.embulk:trino-jdbc:<version>
```

