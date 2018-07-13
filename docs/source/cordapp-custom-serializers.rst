Pluggable Serializers for CorDapps
==================================

.. contents::

To be serializable by Corda Java classes must be compiled with the -parameters switch to enable matching of its properties
to constructor parameters. This is important because Corda's internal AMQP serialization scheme will only construct
objects using their constructors. However, when recompilation isn't possible, or classes are built in such a way that
they cannot be easily modified for simple serialization, CorDapps can provide custom proxy serializers that Corda
can use to move from types it cannot serialize to an interim representation that it can with the transformation to and
from this proxy object being handled by the supplied serializer.

Serializer Location
-------------------
Custom serializer classes should follow the rules for including classes found in :doc:`cordapp-build-systems`

Writing a Custom Serializer
---------------------------
Serializers must
 * Inherit from net.corda.core.serialization.SerializationCustomSerializer
 * Provide a proxy class to transform the object to and from
 * Implement the ``toProxy`` and ``fromProxy`` methods

Serializers inheriting from SerializationCustomSerializer have to implement two methods and two types.

Example
-------
Consider the following class:

.. sourcecode:: java

    public final class Example {
        private final Int a
        private final Int b

        // Because this is marked private the serialization framework will not
        // consider it when looking to see which constructor should be used
        // when serializing instances of this class.
        private Example(Int a, Int b) {
            this.a = a;
            this.b = b;
        }

        public static Example of (int[] a) { return Example(a[0], a[1]); }

        public int getA() { return a; }
        public int getB() { return b; }
    }

Without a custom serializer we cannot serialize this class as there is no public constructor that facilitates the
initialisation of all of its properties.

.. note:: This is clearly a contrived example, simply making the constructor public would alleviate the issues.
    However, for the purposes of this example we are assuming that for external reasons this cannot be done.

To be serializable by Corda this would require a custom serializer to be written that can transform the unserializable
class into a form we can serialize. Continuing the above example, this could be written as follows:

.. sourcecode:: kotlin

    class ExampleSerializer : SerializationCustomSerializer<Example, ExampleSerializer.Proxy> {
        /**
         * This is the actual proxy class that is used as an intermediate representation
         * of the Example class
         */
        data class Proxy(val a: Int, val b: Int)

        /**
         * This method should be able to take an instance of the type being proxied and
         * transpose it into that form, instantiating an instance of the Proxy object (it
         * is this class instance that will be serialized into the byte stream.
         */
        override fun toProxy(obj: Example) = Proxy(obj.a, obj.b)

        /**
         * This method is used during deserialization. The bytes will have been read
         * from the serialized blob and an instance of the Proxy class returned, we must
         * now be able to transform that back into an instance of our original class.
         *
         * In our example this requires us to evoke the static *of* method on the
         * Example class, transforming the serialized properties of the Proxy instance
         * into a form expected by the construction method of Example.
         */
        override fun fromProxy(proxy: Proxy) : Example {
            val constructorArg = IntArray(2);
            constructorArg[0] = proxy.a
            constructorArg[1] = proxy.b
            return Example.of(constructorArg)
        }
    }

In the above ``ExampleSerializer`` is the actual serializer that will be loaded by the framework to
serialize instances of the ``Example`` type.

``ExampleSerializer.Proxy`` is the intermediate representation used by the framework to represent
instances of ``Example`` within the wire format.

The Proxy Object
----------------

The proxy object should be thought of as an intermediate representation that the serialization framework
can reason about. One is being written for a class because, for some reason, that class cannot be
introspected successfully but that framework. It is therefore important to note that the proxy class must
only contain elements that the framework can reason about.

The proxy class itself is distinct from the proxy serializer. The serializer must refer to the unserializable
type in the ``toProxy`` and ``fromProxy`` methods.

For example, the first thought a developer may have when implementing a proxy class is to simply *wrap* an
instance of the object being proxied. This is shown below

.. sourcecode:: kotlin

    class ExampleSerializer : SerializationCustomSerializer<Example, ExampleSerializer.Proxy> {
        /**
         * In this example, we are trying to wrap the Example type to make it serializable
         */
        data class Proxy(val e: Example)

        override fun toProxy(obj: Example) = Proxy(obj)

        override fun fromProxy(proxy: Proxy) : Example {
            return proxy.e
        }
    }

However, this will not work because what we've created is a recursive loop whereby synthesising a serializer
for the ``Example`` type requires synthesising one for ``ExampleSerializer.Proxy``. However, that requires
one for ``Example`` and so on and so forth until we get a ``StackOverflowException``.

The solution, as shown initially, is to create the intermediate form (the Proxy object) purely in terms
the serialization framework can reason about.

.. important:: When composing a proxy object for a class be aware that everything within that structure will be written
    into the serialized byte stream.

Whitelisting
------------
By writing a custom serializer for a class it has the effect of adding that class to the whitelist, meaning such
classes don't need explicitly adding to the CorDapp's whitelist.


