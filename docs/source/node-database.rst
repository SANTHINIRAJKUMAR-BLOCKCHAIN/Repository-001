Node database
=============

.. contents::

Configuring the node database
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

H2
--
By default, nodes store their data in an H2 database. See :doc:`node-database-access-h2`.

Nodes can also be configured to use PostgreSQL and SQL Server. However, these are experimental community contributions.
The Corda continuous integration pipeline does not run unit tests or integration tests of these databases.

PostgreSQL
----------
Nodes can also be configured to use PostgreSQL 9.6, using PostgreSQL JDBC Driver 42.1.4. Here is an example node
configuration for PostgreSQL:

.. sourcecode:: groovy

    dataSourceProperties = {
        dataSourceClassName = "org.postgresql.ds.PGSimpleDataSource"
        dataSource.url = "jdbc:postgresql://[HOST]:[PORT]/[DATABASE]"
        dataSource.user = [USER]
        dataSource.password = [PASSWORD]
    }
    database = {
        transactionIsolationLevel = READ_COMMITTED
    }

Note that:

* Database schema name can be set in JDBC URL string e.g. currentSchema=myschema
* Database schema name must either match the ``dataSource.user`` value to end up
  on the standard schema search path according to the
  `PostgreSQL documentation <https://www.postgresql.org/docs/9.3/static/ddl-schemas.html#DDL-SCHEMAS-PATH>`_, or
  the schema search path must be set explicitly for the user.

SQLServer
---------
Nodes also have untested support for Microsoft SQL Server 2017, using Microsoft JDBC Driver 6.2 for SQL Server. Here is
an example node configuration for SQLServer:

.. sourcecode:: groovy

    dataSourceProperties = {
        dataSourceClassName = "com.microsoft.sqlserver.jdbc.SQLServerDataSource"
        dataSource.url = "jdbc:sqlserver://[HOST]:[PORT];databaseName=[DATABASE_NAME]"
        dataSource.user = [USER]
        dataSource.password = [PASSWORD]
    }
    database = {
        transactionIsolationLevel = READ_COMMITTED
    }
    jarDirs = ["[FULL_PATH]/sqljdbc_6.2/enu/"]

Note that:

* Ensure the directory referenced by jarDirs contains only one JDBC driver JAR file; by the default,
  sqljdbc_6.2/enu/contains two JDBC JAR files for different Java versions.

Node database tables
^^^^^^^^^^^^^^^^^^^^

By default, the node database has the following tables:

+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| Table name                  | Columns                                                                                                                                                                                                  |
+=============================+==========================================================================================================================================================================================================+
| DATABASECHANGELOG           | ID, AUTHOR, FILENAME, DATEEXECUTED, ORDEREXECUTED, EXECTYPE, MD5SUM, DESCRIPTION, COMMENTS, TAG, LIQUIBASE, CONTEXTS, LABELS, DEPLOYMENT_ID                                                              |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| DATABASECHANGELOGLOCK       | ID, LOCKED, LOCKGRANTED, LOCKEDBY                                                                                                                                                                        |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| NODE_ATTACHMENTS            | ATT_ID, CONTENT, FILENAME, INSERTION_DATE, UPLOADER                                                                                                                                                      |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| NODE_ATTACHMENTS_CONTRACTS  | ATT_ID, CONTRACT_CLASS_NAME                                                                                                                                                                              |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| NODE_CHECKPOINTS            | CHECKPOINT_ID, CHECKPOINT_VALUE                                                                                                                                                                          |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| NODE_CONTRACT_UPGRADES      | STATE_REF, CONTRACT_CLASS_NAME                                                                                                                                                                           |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| NODE_IDENTITIES             | PK_HASH, IDENTITY_VALUE                                                                                                                                                                                  |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| NODE_INFOS                  | NODE_INFO_ID, NODE_INFO_HASH, PLATFORM_VERSION, SERIAL                                                                                                                                                   |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| NODE_INFO_HOSTS             | HOST_NAME, PORT, NODE_INFO_ID, HOSTS_ID                                                                                                                                                                  |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| NODE_INFO_PARTY_CERT        | PARTY_NAME, ISMAIN, OWNING_KEY_HASH, PARTY_CERT_BINARY                                                                                                                                                   |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| NODE_LINK_NODEINFO_PARTY    | NODE_INFO_ID, PARTY_NAME                                                                                                                                                                                 |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| NODE_MESSAGE_IDS            | MESSAGE_ID, INSERTION_TIME, SENDER, SEQUENCE_NUMBER                                                                                                                                                      |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| NODE_NAMES_IDENTITIES       | NAME, PK_HASH                                                                                                                                                                                            |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| NODE_OUR_KEY_PAIRS          | PUBLIC_KEY_HASH, PRIVATE_KEY, PUBLIC_KEY                                                                                                                                                                 |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| NODE_PROPERTIES             | PROPERTY_KEY, PROPERTY_VALUE                                                                                                                                                                             |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| NODE_SCHEDULED_STATES       | OUTPUT_INDEXTRANSACTION_IDSCHEDULED_AT                                                                                                                                                                   |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| NODE_TRANSACTIONS           | TX_ID, TRANSACTION_VALUE, STATE_MACHINE_RUN_ID                                                                                                                                                           |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| PK_HASH_TO_EXT_ID_MAP       | ID, EXTERNAL_ID, PUBLIC_KEY_HASH                                                                                                                                                                         |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| STATE_PARTY                 | OUTPUT_INDEX, TRANSACTION_ID, ID, PUBLIC_KEY_HASH, X500_NAME                                                                                                                                             |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| VAULT_FUNGIBLE_STATES       | OUTPUT_INDEX, TRANSACTION_ID, ISSUER_NAME, ISSUER_REF, OWNER_NAME, QUANTITY                                                                                                                              |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| VAULT_FUNGIBLE_STATES_PARTS | OUTPUT_INDEX, TRANSACTION_ID, PARTICIPANTS                                                                                                                                                               |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| VAULT_LINEAR_STATES         | OUTPUT_INDEX, TRANSACTION_ID, EXTERNAL_ID, UUID                                                                                                                                                          |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| VAULT_LINEAR_STATES_PARTS   | OUTPUT_INDEX, TRANSACTION_ID, PARTICIPANTS                                                                                                                                                               |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| VAULT_STATES                | OUTPUT_INDEX, TRANSACTION_ID, CONSUMED_TIMESTAMP, CONTRACT_STATE_CLASS_NAME, LOCK_ID, LOCK_TIMESTAMP, NOTARY_NAME, RECORDED_TIMESTAMP, STATE_STATUS, RELEVANCY_STATUS, CONSTRAINT_TYPE, CONSTRAINT_DATA  |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| VAULT_TRANSACTION_NOTES     | SEQ_NO, NOTE, TRANSACTION_ID                                                                                                                                                                             |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+
| V_PKEY_HASH_EX_ID_MAP       | ID, PUBLIC_KEY_HASH, TRANSACTION_ID, OUTPUT_INDEX, EXTERNAL_ID                                                                                                                                           |
+-----------------------------+----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------+