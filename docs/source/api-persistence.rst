.. highlight:: kotlin
.. raw:: html

   <script type="text/javascript" src="_static/jquery.js"></script>
   <script type="text/javascript" src="_static/codesets.js"></script>

API: Persistence
================

.. contents::

Corda offers developers the option to expose all or some part of a contract state to an *Object Relational Mapping*
(ORM) tool to be persisted in a RDBMS.  The purpose of this is to assist *vault* development by effectively indexing
persisted contract states held in the vault for the purpose of running queries over them and to allow relational joins
between Corda data and private data local to the organisation owning a node.

The ORM mapping is specified using the `Java Persistence API <https://en.wikipedia.org/wiki/Java_Persistence_API>`_
(JPA) as annotations and is converted to database table rows by the node automatically every time a state is recorded
in the node's local vault as part of a transaction.

.. note:: Presently the node includes an instance of the H2 database but any database that supports JDBC is a
          candidate and the node will in the future support a range of database implementations via their JDBC drivers. Much
          of the node internal state is also persisted there. You can access the internal H2 database via JDBC, please see the
          info in ":doc:`node-administration`" for details.

Schemas
-------
Every ``ContractState`` can implement the ``QueryableState`` interface if it wishes to be inserted into the node's local
database and accessible using SQL.

.. literalinclude:: ../../core/src/main/kotlin/net/corda/core/schemas/PersistentTypes.kt
    :language: kotlin
    :start-after: DOCSTART QueryableState
    :end-before: DOCEND QueryableState

The ``QueryableState`` interface requires the state to enumerate the different relational schemas it supports, for
instance in cases where the schema has evolved, with each one being represented by a ``MappedSchema`` object return
by the ``supportedSchemas()`` method.  Once a schema is selected it must generate that representation when requested
via the ``generateMappedObject()`` method which is then passed to the ORM.

Nodes have an internal ``SchemaService`` which decides what to persist and what not by selecting the ``MappedSchema``
to use.

.. literalinclude:: ../../node/src/main/kotlin/net/corda/node/services/api/SchemaService.kt
    :language: kotlin
    :start-after: DOCSTART SchemaService
    :end-before: DOCEND SchemaService

.. literalinclude:: ../../core/src/main/kotlin/net/corda/core/schemas/PersistentTypes.kt
    :language: kotlin
    :start-after: DOCSTART MappedSchema
    :end-before: DOCEND MappedSchema

The ``SchemaService`` can be configured by a node administrator to select the schemas used by each app. In this way the
relational view of ledger states can evolve in a controlled fashion in lock-step with internal systems or other
integration points and not necessarily with every upgrade to the contract code. It can select from the
``MappedSchema`` offered by a ``QueryableState``, automatically upgrade to a later version of a schema or even
provide a ``MappedSchema`` not originally offered by the ``QueryableState``.

It is expected that multiple different contract state implementations might provide mappings within a single schema.
For example an Interest Rate Swap contract and an Equity OTC Option contract might both provide a mapping to
a Derivative contract within the same schema. The schemas should typically not be part of the contract itself and should exist independently
to encourage re-use of a common set within a particular business area or Cordapp.

.. note:: It's advisable to avoid cross-references between different schemas as this may cause issues when evolving ``MappedSchema``
   or migrating its data. At startup, nodes log such violations as warnings stating that there's a cross-reference between ``MappedSchema``'s.
   The detailed messages incorporate information about what schemas, entities and fields are involved.

``MappedSchema`` offer a family name that is disambiguated using Java package style name-spacing derived from the
class name of a *schema family* class that is constant across versions, allowing the ``SchemaService`` to select a
preferred version of a schema.

The ``SchemaService`` is also responsible for the ``SchemaOptions`` that can be configured for a particular
``MappedSchema`` which allow the configuration of a database schema or table name prefixes to avoid any clash with
other ``MappedSchema``.

.. note:: It is intended that there should be plugin support for the ``SchemaService`` to offer the version upgrading
   and additional schemas as part of Cordapps, and that the active schemas be configurable.  However the present
   implementation offers none of this and simply results in all versions of all schemas supported by a
   ``QueryableState`` being persisted. This will change in due course. Similarly, it does not currently support
   configuring ``SchemaOptions`` but will do so in the future.

Custom schema registration
--------------------------
Custom contract schemas are automatically registered at startup time for CorDapps. The node bootstrap process will scan
for schemas (any class that extends the ``MappedSchema`` interface) in the `plugins` configuration directory in your CorDapp jar.

For testing purposes it is necessary to manually register the packages containing custom schemas as follows:

- Tests using ``MockNetwork`` and ``MockNode`` must explicitly register packages using the `cordappPackages` parameter of ``MockNetwork``
- Tests using ``MockServices`` must explicitly register packages using the `cordappPackages` parameter of the ``MockServices`` `makeTestDatabaseAndMockServices()` helper method.

.. note:: Tests using the `DriverDSL` will automatically register your custom schemas if they are in the same project structure as the driver call.

Object relational mapping
-------------------------
The persisted representation of a ``QueryableState`` should be an instance of a ``PersistentState`` subclass,
constructed either by the state itself or a plugin to the ``SchemaService``.  This allows the ORM layer to always
associate a ``StateRef`` with a persisted representation of a ``ContractState`` and allows joining with the set of
unconsumed states in the vault.

The ``PersistentState`` subclass should be marked up as a JPA 2.1 *Entity* with a defined table name and having
properties (in Kotlin, getters/setters in Java) annotated to map to the appropriate columns and SQL types. Additional
entities can be included to model these properties where they are more complex, for example collections, so the mapping
does not have to be *flat*. The ``MappedSchema`` must provide a list of all of the JPA entity classes for that schema
in order to initialise the ORM layer.

Several examples of entities and mappings are provided in the codebase, including ``Cash.State`` and
``CommercialPaper.State``. For example, here's the first version of the cash schema.

.. literalinclude:: ../../finance/src/main/kotlin/net/corda/finance/schemas/CashSchemaV1.kt
    :language: kotlin

.. note:: If Cordapp needs to be portable between Corda OS (running against H2) and Corda Enterprise (running against a standalone database),
          consider database vendors specific requirements.
          Ensure that table and column names are compatible with the naming convention of the database vendors for which the Cordapp will be deployed,
          e.g. for Oracle database, prior to version 12.2 the maximum length of table/column name is 30 bytes (the exact number of characters depends on the database encoding).

Identity mapping
----------------
Schema entity attributes defined by identity types (``AbstractParty``, ``Party``, ``AnonymousParty``) are automatically
processed to ensure only the ``X500Name`` of the identity is persisted where an identity is well known, otherwise a null
value is stored in the associated column. To preserve privacy, identity keys are never persisted. Developers should use
the ``IdentityService`` to resolve keys from well know X500 identity names.

.. _jdbc_session_ref:

JDBC session
------------
Apps may also interact directly with the underlying Node's database by using a standard
JDBC connection (session) as described by the `Java SQL Connection API <https://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html>`_

Use the ``ServiceHub`` ``jdbcSession`` function to obtain a JDBC connection as illustrated in the following example:

.. literalinclude:: ../../node/src/test/kotlin/net/corda/node/services/persistence/HibernateConfigurationTest.kt
  :language: kotlin
  :start-after: DOCSTART JdbcSession
  :end-before: DOCEND JdbcSession

JDBC sessions can be used in flows and services (see ":doc:`flow-state-machines`").

The following example illustrates the creation of a custom Corda service using a ``jdbcSession``:

.. literalinclude:: ../../docs/source/example-code/src/main/kotlin/net/corda/docs/kotlin/vault/CustomVaultQuery.kt
  :language: kotlin
  :start-after: DOCSTART CustomVaultQuery
  :end-before: DOCEND CustomVaultQuery

which is then referenced within a custom flow:

.. literalinclude:: ../../docs/source/example-code/src/main/kotlin/net/corda/docs/kotlin/vault/CustomVaultQuery.kt
  :language: kotlin
  :start-after: DOCSTART TopupIssuer
  :end-before: DOCEND TopupIssuer

For examples on testing ``@CordaService`` implementations, see the oracle example :doc:`here <oracles>`.

JPA Support
-----------
In addition to ``jdbcSession``, ``ServiceHub`` also exposes the Java Persistence API to flows via the ``withEntityManager``
method. This method can be used to persist and query entities which inherit from ``MappedSchema``. This is particularly
useful if off-ledger data must be maintained in conjunction with on-ledger state data.

    .. note:: Your entity must be included as a mappedType in as part of a MappedSchema for it to be added to Hibernate
              as a custom schema. See Samples below.

The code snippet below defines a ``PersistentFoo`` type inside ``FooSchemaV1``. Note that ``PersistentFoo`` is added to
a list of mapped types which is passed to ``MappedSChema``. This is exactly how state schemas are defined, except that
the entity in this case should not subclass ``PersistentState`` (as it is not a state object). See examples:

.. container:: codeset

    .. sourcecode:: java

        public class FooSchema {}

        @CordaSerializable
        public class FooSchemaV1 extends MappedSchema {
            FooSchemaV1() {
                super(FooSchema.class, 1, ImmutableList.of(PersistentFoo.class));
            }

            @Entity
            @Table(name = "foos")
            class PersistentFoo implements Serializable {
                @Id
                @Column(name = "foo_id")
                String fooId;

                @Column(name = "foo_data")
                String fooData;
            }
        }

    .. sourcecode:: kotlin

        object FooSchema

        object FooSchemaV1 : MappedSchema(schemaFamily = FooSchema.javaClass, version = 1, mappedTypes = listOf(PersistentFoo::class.java)) {
            @Entity
            @Table(name = "foos")
            class PersistentFoo(@Id @Column(name = "foo_id") var fooId: String, @Column(name = "foo_data") var fooData: String) : Serializable
        }

Instances of ``PersistentFoo`` can be persisted inside a flow as follows:

.. container:: codeset

    .. sourcecode:: java

        PersistentFoo foo = new PersistentFoo(new UniqueIdentifier().getId().toString(), "Bar");
        node.getServices().withEntityManager(entityManager -> {
            entityManager.persist(foo);
            entityManager.flush();
            return null;
        });

    .. sourcecode:: kotlin

        val foo = FooSchemaV1.PersistentFoo(UniqueIdentifier().id.toString(), "Bar")
        serviceHub.withEntityManager {
            persist(foo)
        }

And retrieved via a query, as follows:

.. container:: codeset

    .. sourcecode:: java

        node.getServices().withEntityManager((EntityManager entityManager) -> {
            CriteriaQuery<PersistentFoo> query = entityManager.getCriteriaBuilder().createQuery(PersistentFoo.class);
            Root<PersistentFoo> type = query.from(PersistentFoo.class);
            query.select(type);
            return entityManager.createQuery(query).getResultList();
        });

    .. sourcecode:: kotlin

        val result: MutableList<FooSchemaV1.PersistentFoo> = services.withEntityManager {
            val query = criteriaBuilder.createQuery(FooSchemaV1.PersistentFoo::class.java)
            val type = query.from(FooSchemaV1.PersistentFoo::class.java)
            query.select(type)
            createQuery(query).resultList
        }

Please note that suspendable flow operations such as:

* ``FlowSession.send``
* ``FlowSession.receive``
* ``FlowLogic.receiveAll``
* ``FlowLogic.sleep``
* ``FlowLogic.subFlow``

Cannot be used within the lambda function passed to ``withEntityManager``.
