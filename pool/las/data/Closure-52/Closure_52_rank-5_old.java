package com.fasterxml.jackson.databind.type;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.util.ArrayBuilders;
import com.fasterxml.jackson.databind.util.ClassUtil;
import com.fasterxml.jackson.databind.util.LRUMap;

/**
 * Class used for creating concrete {@link JavaType} instances,
 * given various inputs.
 *<p>
 * Instances of this class are accessible using {@link com.fasterxml.jackson.databind.ObjectMapper}
 * as well as many objects it constructs (like
* {@link com.fasterxml.jackson.databind.DeserializationConfig} and
 * {@link com.fasterxml.jackson.databind.SerializationConfig})),
 * but usually those objects also 
 * expose convenience methods (<code>constructType</code>).
 * So, you can do for example:
 *<pre>
 *   JavaType stringType = mapper.constructType(String.class);
 *</pre>
 * However, more advanced methods are only exposed by factory so that you
 * may need to use:
 *<pre>
 *   JavaType stringCollection = mapper.getTypeFactory().constructCollectionType(List.class, String.class);
 *</pre>
 */
@SuppressWarnings({"rawtypes" })
public final class TypeFactory
    implements java.io.Serializable
{
    private static final long serialVersionUID = 1L;

    private final static JavaType[] NO_TYPES = new JavaType[0];

    /**
     * Globally shared singleton. Not accessed directly; non-core
     * code should use per-ObjectMapper instance (via configuration objects).
     * Core Jackson code uses {@link #defaultInstance} for accessing it.
     */
    protected final static TypeFactory instance = new TypeFactory();

    protected final static TypeBindings EMPTY_BINDINGS = TypeBindings.emptyBindings();

    /*
    /**********************************************************
    /* Caching
    /**********************************************************
     */

    // // // Let's assume that a small set of core primitive/basic types
    // // // will not be modified, and can be freely shared to streamline
    // // // parts of processing

    private final static Class<?> CLS_STRING = String.class;
    private final static Class<?> CLS_OBJECT = Object.class;

    private final static Class<?> CLS_BOOL = Boolean.TYPE;
    private final static Class<?> CLS_INT = Integer.TYPE;
    private final static Class<?> CLS_LONG = Long.TYPE;

    // note: these are primitive, hence no super types
    protected final static SimpleType CORE_TYPE_BOOL = new SimpleType(CLS_BOOL);
    protected final static SimpleType CORE_TYPE_INT = new SimpleType(CLS_INT);
    protected final static SimpleType CORE_TYPE_LONG = new SimpleType(CLS_LONG);

    // and as to String... well, for now, ignore its super types
    protected final static SimpleType CORE_TYPE_STRING = new SimpleType(CLS_STRING);
    
    /**
     * @since 2.7
     */
    protected final static SimpleType CORE_TYPE_OBJECT = new SimpleType(CLS_OBJECT);

    /**
     * Since type resolution can be expensive (specifically when resolving
     * actual generic types), we will use small cache to avoid repetitive
     * resolution of core types
     */
    protected final LRUMap<ClassKey, JavaType> _typeCache = new LRUMap<ClassKey, JavaType>(16, 100);

    /*
    /**********************************************************
    /* Configuration
    /**********************************************************
     */

    /**
     * Registered {@link TypeModifier}s: objects that can change details
     * of {@link JavaType} instances factory constructs.
     */
    protected final TypeModifier[] _modifiers;

    protected final TypeParser _parser;
    
    /**
     * ClassLoader used by this factory [databind#624].
     */
    protected final ClassLoader _classLoader;

    /*
    /**********************************************************
    /* Life-cycle
    /**********************************************************
     */

    private TypeFactory() {
        _parser = new TypeParser(this);
        _modifiers = null;
        _classLoader = null;
    }

    protected TypeFactory(TypeParser p, TypeModifier[] mods) {
        this(p, mods, null);
    }
    
    protected TypeFactory(TypeParser p, TypeModifier[] mods, ClassLoader classLoader) {
        // As per [databind#894] must ensure we have back-linkage from TypeFactory:
        _parser = p.withFactory(this);
        _modifiers = mods;
        _classLoader = classLoader;
    }

    public TypeFactory withModifier(TypeModifier mod) 
    {
        if (mod == null) { // mostly for unit tests
            return new TypeFactory(_parser, _modifiers, _classLoader);
        }
        if (_modifiers == null) {
            return new TypeFactory(_parser, new TypeModifier[] { mod }, _classLoader);
        }
        return new TypeFactory(_parser, ArrayBuilders.insertInListNoDup(_modifiers, mod), _classLoader);
    }
    
    public TypeFactory withClassLoader(ClassLoader classLoader) {
        return new TypeFactory(_parser, _modifiers, classLoader);
    }

    /**
     * Method used to access the globally shared instance, which has
     * no custom configuration. Used by <code>ObjectMapper</code> to
     * get the default factory when constructed.
     */
    public static TypeFactory defaultInstance() { return instance; }

    /**
     * Method that will clear up any cached type definitions that may
     * be cached by this {@link TypeFactory} instance.
     * This method should not be commonly used, that is, only use it
     * if you know there is a problem with retention of type definitions;
     * the most likely (and currently only known) problem is retention
     * of {@link Class} instances via {@link JavaType} reference.
     * 
     * @since 2.4.1
     */
    public void clearCache() {
        _typeCache.clear();
    }
    
    /*
     * Getters
     */
    public ClassLoader getClassLoader() {
        return _classLoader;
    }
    
    /*
    /**********************************************************
    /* Static methods for non-instance-specific functionality
    /**********************************************************
     */
    
    /**
     * Method for constructing a marker type that indicates missing generic
     * type information, which is handled same as simple type for
     * <code>java.lang.Object</code>.
     */
    public static JavaType unknownType() {
        return defaultInstance()._unknownType();
    }

    /**
     * Static helper method that can be called to figure out type-erased
     * call for given JDK type. It can be called statically since type resolution
     * process can never change actual type-erased class; thereby static
     * default instance is used for determination.
     */
    public static Class<?> rawClass(Type t) {
        if (t instanceof Class<?>) {
            return (Class<?>) t;
        }
        // Should be able to optimize bit more in future...
        return defaultInstance().constructType(t).getRawClass();
    }

    /*
    /**********************************************************
    /* Low-level helper methods
    /**********************************************************
     */

    /**
     * Low-level lookup method moved from {@link com.fasterxml.jackson.databind.util.ClassUtil},
     * to allow for overriding of lookup functionality in environments like OSGi.
     *
     * @since 2.6
     */
    public Class<?> findClass(String className) throws ClassNotFoundException
    {
        if (className.indexOf('.') < 0) {
            Class<?> prim = _findPrimitive(className);
            if (prim != null) {
                return prim;
            }
        }
        // Two-phase lookup: first using context ClassLoader; then default
        Throwable prob = null;
        ClassLoader loader = this.getClassLoader();
        if (loader == null) {
          loader = 	Thread.currentThread().getContextClassLoader();
        }
        if (loader != null) {
            try {
                return classForName(className, true, loader);
            } catch (Exception e) {
                prob = ClassUtil.getRootCause(e);
            }
        }
        try {
            return classForName(className);
        } catch (Exception e) {
            if (prob == null) {
                prob = ClassUtil.getRootCause(e);
            }
        }
        if (prob instanceof RuntimeException) {
            throw (RuntimeException) prob;
        }
        throw new ClassNotFoundException(prob.getMessage(), prob);
    }
    
    protected Class<?> classForName(String name, boolean initialize,
                                   ClassLoader loader) throws ClassNotFoundException {
    	return Class.forName(name, true, loader);
    }
    
    protected Class<?> classForName(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    protected Class<?> _findPrimitive(String className)
    {
        if ("int".equals(className)) return Integer.TYPE;
        if ("long".equals(className)) return Long.TYPE;
        if ("float".equals(className)) return Float.TYPE;
        if ("double".equals(className)) return Double.TYPE;
        if ("boolean".equals(className)) return Boolean.TYPE;
        if ("byte".equals(className)) return Byte.TYPE;
        if ("char".equals(className)) return Character.TYPE;
        if ("short".equals(className)) return Short.TYPE;
        if ("void".equals(className)) return Void.TYPE;
        return null;
    }
    
    /*
    /**********************************************************
    /* Type conversion, parameterization resolution methods
    /**********************************************************
     */

    /**
     * Factory method for creating a subtype of given base type, as defined
     * by specified subclass; but retaining generic type information if any.
     * Can be used, for example, to get equivalent of "HashMap&lt;String,Integer&gt;"
     * from "Map&lt;String,Integer&gt;" by giving <code>HashMap.class</code>
     * as subclass.
     */
    public JavaType constructSpecializedType(JavaType baseType, Class<?> subclass)
    {
        // simple optimization to avoid costly introspection if type-erased type does NOT differ
        final Class<?> rawBase = baseType.getRawClass();
        if (rawBase == subclass) {
            return baseType;
        }

        JavaType newType;

        // also: if we start from untyped, not much to save
        do { // bogus loop to be able to break
            if (rawBase == Object.class) {
                newType = _fromClass(null, subclass, TypeBindings.emptyBindings());
                break;
            }
            if (!rawBase.isAssignableFrom(subclass)) {
                throw new IllegalArgumentException("Class "+subclass.getName()+" not subtype of "+baseType);
            }
            // A few special cases where we can simplify handling:

            // (1) Original target type has no generics -- just resolve subtype
            // (2) Sub-class does not take type parameters -- just resolve subtype
            if (baseType.getBindings().isEmpty()
                    || (subclass.getTypeParameters().length == 0)) {
                newType = _fromClass(null, subclass, TypeBindings.emptyBindings());     
                break;
            }
            // (3) A small set of "well-known" List/Map subtypes where can take a short-cut
            if (baseType.isContainerType()) {
                if (baseType.isMapLikeType()) {
                    if ((subclass == HashMap.class)
                            || (subclass == LinkedHashMap.class)
                            || (subclass == EnumMap.class)
                            || (subclass == TreeMap.class)) {
                        newType = _fromClass(null, subclass,
                                TypeBindings.create(subclass, baseType.getKeyType(), baseType.getContentType()));
                        break;
                    }
                } else if (baseType.isCollectionLikeType()) {
                    if ((subclass == ArrayList.class)
                            || (subclass == LinkedList.class)
                            || (subclass == HashSet.class)
                            || (subclass == TreeSet.class)) {
                        newType = _fromClass(null, subclass,
                                TypeBindings.create(subclass, baseType.getContentType()));
                        break;
                    }
                    // 29-Oct-2015, tatu: One further shortcut: there are variants of `EnumSet`,
                    //    but they are impl details and we basically do not care...
                    if (rawBase == EnumSet.class) {
                        return baseType;
                    }
                }
            }

            // If not, we'll need to do more thorough forward+backwards resolution. Sigh.
            // !!! TODO
            
            // 20-Oct-2015, tatu: Container, Map-types somewhat special. There is
            //    a way to fully resolve and merge hierarchies; but that gets expensive
            //    so let's, for now, try to create close-enough approximation that
            //    is not 100% same, structurally, but has equivalent information for
            //    our specific neeeds.
            if (baseType.isInterface()) {
                newType = baseType.refine(subclass, TypeBindings.emptyBindings(), null,
                        new JavaType[] { baseType });
            } else {
                newType = baseType.refine(subclass, TypeBindings.emptyBindings(), baseType,
                        NO_TYPES);
            }
            // Only SimpleType returns null, but if so just resolve regularly
            if (newType == null) {
                // But otherwise gets bit tricky, as we need to partially resolve the type hierarchy
                // (hopefully passing null Class for root is ok)
                newType = _fromClass(null, subclass, TypeBindings.emptyBindings());        
            }
        } while (false);

        // except possibly handlers
//      newType = newType.withHandlersFrom(baseType);
        return newType;

        // 20-Oct-2015, tatu: Old simplistic approach
        
        /*
        // Currently mostly SimpleType instances can become something else
        if (baseType instanceof SimpleType) {
            // and only if subclass is an array, Collection or Map
            if (subclass.isArray()
                || Map.class.isAssignableFrom(subclass)
                || Collection.class.isAssignableFrom(subclass)) {
                // need to assert type compatibility...
                if (!baseType.getRawClass().isAssignableFrom(subclass)) {
                    throw new IllegalArgumentException("Class "+subclass.getClass().getName()+" not subtype of "+baseType);
                }
                // this _should_ work, right?
                JavaType subtype = _fromClass(null, subclass, TypeBindings.emptyBindings());
                // one more thing: handlers to copy?
                Object h = baseType.getValueHandler();
                if (h != null) {
                    subtype = subtype.withValueHandler(h);
                }
                h = baseType.getTypeHandler();
                if (h != null) {
                    subtype = subtype.withTypeHandler(h);
                }
                return subtype;
            }
        }
        // But there is the need for special case for arrays too, it seems
        if (baseType instanceof ArrayType) {
            if (subclass.isArray()) {
                // actually see if it might be a no-op first:
                ArrayType at = (ArrayType) baseType;
                Class<?> rawComp = subclass.getComponentType();
                if (at.getContentType().getRawClass() == rawComp) {
                    return baseType;
                }
                JavaType componentType = _fromAny(null, rawComp, null);
                return ((ArrayType) baseType).withOverriddenComponentType(componentType);
            }
        }

        // otherwise regular narrowing should work just fine
        return baseType.narrowBy(subclass);
        */
    }

    /**
     * Factory method for constructing a {@link JavaType} out of its canonical
     * representation (see {@link JavaType#toCanonical()}).
     * 
     * @param canonical Canonical string representation of a type
     * 
     * @throws IllegalArgumentException If canonical representation is malformed,
     *   or class that type represents (including its generic parameters) is
     *   not found
     */
    public JavaType constructFromCanonical(String canonical) throws IllegalArgumentException
    {
        return _parser.parse(canonical);
    }

    /**
     * Method that is to figure out actual type parameters that given
     * class binds to generic types defined by given (generic)
     * interface or class.
     * This could mean, for example, trying to figure out
     * key and value types for Map implementations.
     * 
     * @param type Sub-type (leaf type) that implements <code>expType</code>
     */
    public JavaType[] findTypeParameters(JavaType type, Class<?> expType)
    {
        JavaType match = type.findSuperType(expType);
        if (match == null) {
            return NO_TYPES;
        }
        return match.getBindings().typeParameterArray();
    }

    /**
     * @deprecated Since 2.7 resolve raw type first, then find type parameters
     */
    @Deprecated // since 2.7    
    public JavaType[] findTypeParameters(Class<?> clz, Class<?> expType, TypeBindings bindings) {
        return findTypeParameters(constructType(clz, bindings), expType);
    }
    
    /**
     * @deprecated Since 2.7 resolve raw type first, then find type parameters
     */
    @Deprecated // since 2.7    
    public JavaType[] findTypeParameters(Class<?> clz, Class<?> expType) {
        return findTypeParameters(constructType(clz), expType);
    }

    /**
     * Method that can be called to figure out more specific of two
     * types (if they are related; that is, one implements or extends the
     * other); or if not related, return the primary type.
     * 
     * @param type1 Primary type to consider
     * @param type2 Secondary type to consider
     * 
     * @since 2.2
     */
    public JavaType moreSpecificType(JavaType type1, JavaType type2)
    {
        if (type1 == null) {
            return type2;
        }
        if (type2 == null) {
            return type1;
        }
        Class<?> raw1 = type1.getRawClass();
        Class<?> raw2 = type2.getRawClass();
        if (raw1 == raw2) {
            return type1;
        }
        // TODO: maybe try sub-classing, to retain generic types?
        if (raw1.isAssignableFrom(raw2)) {
            return type2;
        }
        return type1;
    }
    
    /*
    /**********************************************************
    /* Public factory methods
    /**********************************************************
     */

    public JavaType constructType(Type type) {
        return _fromAny(null, type, EMPTY_BINDINGS);
    }

    public JavaType constructType(Type type, TypeBindings bindings) {
        return _fromAny(null, type, bindings);
    }
    
    public JavaType constructType(TypeReference<?> typeRef)
    {
        // 19-Oct-2015, tatu: Simpler variant like so should work
        return _fromAny(null, typeRef.getType(), EMPTY_BINDINGS);

        // but if not, due to funky sub-classing, type variables, what follows
        // is a more complete processing a la Java ClassMate.

        /*
        final Class<?> refdRawType = typeRef.getClass();
        JavaType type = _fromClass(null, refdRawType, EMPTY_BINDINGS);
        JavaType genType = type.findSuperType(TypeReference.class);
        if (genType == null) { // sanity check; shouldn't occur
            throw new IllegalArgumentException("Unparameterized GenericType instance ("+refdRawType.getName()+")");
        }
        TypeBindings b = genType.getBindings();
        JavaType[] params = b.typeParameterArray();
        if (params.length == 0) {
            throw new IllegalArgumentException("Unparameterized GenericType instance ("+refdRawType.getName()+")");
        }
        return params[0];
        */
    }

    /*
    public JavaType constructType(Type type, Class<?> contextType) {
        TypeBindings b = (contextType == null) ? null : new TypeBindings(this, null, contextType);
        return _fromAny(null, type, b);
    }

    public JavaType constructType(Type type, JavaType contextType) {
        TypeBindings b = (contextType == null) ? null : new TypeBindings(this, null, contextType);
        return _fromAny(null, type, b);
    }
    */

    /*
    /**********************************************************
    /* Direct factory methods
    /**********************************************************
     */

    /**
     * Method for constructing an {@link ArrayType}.
     *<p>
     * NOTE: type modifiers are NOT called on array type itself; but are called
     * for element type (and other contained types)
     */
    public ArrayType constructArrayType(Class<?> elementType) {
        return ArrayType.construct(_fromAny(null, elementType, null), null);
    }
    
    /**
     * Method for constructing an {@link ArrayType}.
     *<p>
     * NOTE: type modifiers are NOT called on array type itself; but are called
     * for contained types.
     */
    public ArrayType constructArrayType(JavaType elementType) {
        return ArrayType.construct(elementType, null);
    }

    /**
     * Method for constructing a {@link CollectionType}.
     *<p>
     * NOTE: type modifiers are NOT called on Collection type itself; but are called
     * for contained types.
     */
    public CollectionType constructCollectionType(Class<? extends Collection> collectionClass, Class<?> elementClass) {
        return constructCollectionType(collectionClass,
                _fromClass(null, elementClass, EMPTY_BINDINGS));
    }

    /**
     * Method for constructing a {@link CollectionType}.
     *<p>
     * NOTE: type modifiers are NOT called on Collection type itself; but are called
     * for contained types.
     */
    public CollectionType constructCollectionType(Class<? extends Collection> collectionClass, JavaType elementType) {
        // 19-Oct-2015, tatu: Allow case of no-type-variables, since it seems likely to be
        //    a valid use case here
        return (CollectionType) _fromClass(null, collectionClass,
                TypeBindings.create(collectionClass, elementType));
    }

    /**
     * Method for constructing a {@link CollectionLikeType}.
     *<p>
     * NOTE: type modifiers are NOT called on constructed type itself; but are called
     * for contained types.
     */
    public CollectionLikeType constructCollectionLikeType(Class<?> collectionClass, Class<?> elementClass) {
        return constructCollectionLikeType(collectionClass,
                _fromClass(null, elementClass, EMPTY_BINDINGS));
    }
    
    /**
     * Method for constructing a {@link CollectionLikeType}.
     *<p>
     * NOTE: type modifiers are NOT called on constructed type itself; but are called
     * for contained types.
     */
    public CollectionLikeType constructCollectionLikeType(Class<?> collectionClass, JavaType elementType) {
        JavaType type = _fromClass(null, collectionClass,
                TypeBindings.createIfNeeded(collectionClass, elementType));
        if (type instanceof CollectionLikeType) {
            return (CollectionLikeType) type;
        }
        return CollectionLikeType.upgradeFrom(type, elementType);
    }

    /**
     * Method for constructing a {@link MapType} instance
     *<p>
     * NOTE: type modifiers are NOT called on constructed type itself; but are called
     * for contained types.
     */
    public MapType constructMapType(Class<? extends Map> mapClass, Class<?> keyClass, Class<?> valueClass) {
        JavaType kt, vt;
        if (mapClass == Properties.class) {
            kt = vt = CORE_TYPE_STRING;
        } else {
            kt = _fromClass(null, keyClass, EMPTY_BINDINGS);
            vt = _fromClass(null, valueClass, EMPTY_BINDINGS);
        }
        return constructMapType(mapClass, kt, vt);
    }

    /**
     * Method for constructing a {@link MapType} instance
     *<p>
     * NOTE: type modifiers are NOT called on constructed type itself; but are called
     * for contained types.
     */
    public MapType constructMapType(Class<? extends Map> mapClass, JavaType keyType, JavaType valueType) {
        return (MapType) _fromClass(null, mapClass,
                TypeBindings.create(mapClass, new JavaType[] {
                        keyType, valueType
                }));
    }

    /**
     * Method for constructing a {@link MapLikeType} instance
     *<p>
     * NOTE: type modifiers are NOT called on constructed type itself; but are called
     * for contained types.
     */
    public MapLikeType constructMapLikeType(Class<?> mapClass, Class<?> keyClass, Class<?> valueClass) {
        return constructMapLikeType(mapClass,
                _fromClass(null, keyClass, EMPTY_BINDINGS),
                _fromClass(null, valueClass, EMPTY_BINDINGS));
    }

    /**
     * Method for constructing a {@link MapLikeType} instance
     *<p>
     * NOTE: type modifiers are NOT called on constructed type itself; but are called
     * for contained types.
     */
    public MapLikeType constructMapLikeType(Class<?> mapClass, JavaType keyType, JavaType valueType) {
        // 19-Oct-2015, tatu: Allow case of no-type-variables, since it seems likely to be
        //    a valid use case here
        JavaType type = _fromClass(null, mapClass,
                TypeBindings.createIfNeeded(mapClass, new JavaType[] { keyType, valueType }));
        if (type instanceof MapLikeType) {
            return (MapLikeType) type;
        }
        return MapLikeType.upgradeFrom(type, keyType, valueType);
    }

    /**
     * Method for constructing a type instance with specified parameterization.
     *<p>
     * NOTE: was briefly deprecated for 2.6.
     */
    public JavaType constructSimpleType(Class<?> rawType, JavaType[] parameterTypes) {
        return _fromClass(null, rawType, TypeBindings.create(rawType, parameterTypes));
    }

    /**
     * Method for constructing a type instance with specified parameterization.
     *
     * @since 2.6
     *
     * @deprecated Since 2.7
     */
    @Deprecated
    public JavaType constructSimpleType(Class<?> rawType, Class<?> parameterTarget,
            JavaType[] parameterTypes)
    {
        return constructSimpleType(rawType, parameterTypes);
    } 

    /**
     * @since 2.6
     */
    public JavaType constructReferenceType(Class<?> rawType, JavaType referredType)
    {
        return ReferenceType.construct(rawType, null, // no bindings
                null, null, // or super-class, interfaces?
                referredType);
    }

    /**
     * Method that will force construction of a simple type, without trying to
     * check for more specialized types.
     *<p> 
     * NOTE: no type modifiers are called on type either, so calling this method
     * should only be used if caller really knows what it's doing...
     */
    public JavaType uncheckedSimpleType(Class<?> cls) {
        // 18-Oct-2015, tatu: Not sure how much problem missing super-type info is here
        return _constructSimple(cls, EMPTY_BINDINGS, null, null);
    }

    /**
     * Factory method for constructing {@link JavaType} that
     * represents a parameterized type. For example, to represent
     * type <code>List&lt;Set&lt;Integer>></code>, you could
     * call
     *<pre>
     *  JavaType inner = TypeFactory.constructParametrizedType(Set.class, Set.class, Integer.class);
     *  return TypeFactory.constructParametrizedType(ArrayList.class, List.class, inner);
     *</pre>
     *<p>
     * The reason for first two arguments to be separate is that parameterization may
     * apply to a super-type. For example, if generic type was instead to be
     * constructed for <code>ArrayList&lt;Integer></code>, the usual call would be:
     *<pre>
     *  TypeFactory.constructParametrizedType(ArrayList.class, List.class, Integer.class);
     *</pre>
     * since parameterization is applied to {@link java.util.List}.
     * In most cases distinction does not matter, but there are types where it does;
     * one such example is parameterization of types that implement {@link java.util.Iterator}.
     *<p>
     * NOTE: type modifiers are NOT called on constructed type.
     * 
     * @param parametrized Actual full type
     * @param parameterClasses Type parameters to apply
     *
     * @since 2.5 NOTE: was briefly deprecated for 2.6
     */
    public JavaType constructParametricType(Class<?> parametrized, Class<?>... parameterClasses) {
        int len = parameterClasses.length;
        JavaType[] pt = new JavaType[len];
        for (int i = 0; i < len; ++i) {
            pt[i] = _fromClass(null, parameterClasses[i], null);
        }
        return constructParametricType(parametrized, pt);
    }

    /**
     * Factory method for constructing {@link JavaType} that
     * represents a parameterized type. For example, to represent
     * type <code>List&lt;Set&lt;Integer>></code>, you could
     * call
     *<pre>
     *  JavaType inner = TypeFactory.constructParametrizedType(Set.class, Set.class, Integer.class);
     *  return TypeFactory.constructParametrizedType(ArrayList.class, List.class, inner);
     *</pre>
     *<p>
     * The reason for first two arguments to be separate is that parameterization may
     * apply to a super-type. For example, if generic type was instead to be
     * constructed for <code>ArrayList&lt;Integer></code>, the usual call would be:
     *<pre>
     *  TypeFactory.constructParametrizedType(ArrayList.class, List.class, Integer.class);
     *</pre>
     * since parameterization is applied to {@link java.util.List}.
     * In most cases distinction does not matter, but there are types where it does;
     * one such example is parameterization of types that implement {@link java.util.Iterator}.
     *<p>
     * NOTE: type modifiers are NOT called on constructed type.
     * 
     * @param rawType Actual type-erased type
     * @param parameterTypes Type parameters to apply
     * 
     * @since 2.5 NOTE: was briefly deprecated for 2.6
     */
    public JavaType constructParametricType(Class<?> rawType, JavaType... parameterTypes)
    {
        return _fromClass(null, rawType, TypeBindings.create(rawType, parameterTypes));
    }

    /**
     * @since 2.5 -- but will probably deprecated in 2.7 or 2.8 (not needed with 2.7)
     */
    public JavaType constructParametrizedType(Class<?> parametrized, Class<?> parametersFor,
            JavaType... parameterTypes)
    {
        return constructParametricType(parametrized, parameterTypes);
    }

    /**
     * @since 2.5 -- but will probably deprecated in 2.7 or 2.8 (not needed with 2.7)
     */
    public JavaType constructParametrizedType(Class<?> parametrized, Class<?> parametersFor,
            Class<?>... parameterClasses)
    {
        return constructParametricType(parametrized, parameterClasses);
    }

    /*
    /**********************************************************
    /* Direct factory methods for "raw" variants, used when
    /* parameterization is unknown
    /**********************************************************
     */

    /**
     * Method that can be used to construct "raw" Collection type; meaning that its
     * parameterization is unknown.
     * This is similar to using <code>Object.class</code> parameterization,
     * and is equivalent to calling:
     *<pre>
     *  typeFactory.constructCollectionType(collectionClass, typeFactory.unknownType());
     *</pre>
     *<p>
     * This method should only be used if parameterization is completely unavailable.
     */
    public CollectionType constructRawCollectionType(Class<? extends Collection> collectionClass) {
        return constructCollectionType(collectionClass, unknownType());
    }

    /**
     * Method that can be used to construct "raw" Collection-like type; meaning that its
     * parameterization is unknown.
     * This is similar to using <code>Object.class</code> parameterization,
     * and is equivalent to calling:
     *<pre>
     *  typeFactory.constructCollectionLikeType(collectionClass, typeFactory.unknownType());
     *</pre>
     *<p>
     * This method should only be used if parameterization is completely unavailable.
     */
    public CollectionLikeType constructRawCollectionLikeType(Class<?> collectionClass) {
        return constructCollectionLikeType(collectionClass, unknownType());
    }

    /**
     * Method that can be used to construct "raw" Map type; meaning that its
     * parameterization is unknown.
     * This is similar to using <code>Object.class</code> parameterization,
     * and is equivalent to calling:
     *<pre>
     *  typeFactory.constructMapType(collectionClass, typeFactory.unknownType(), typeFactory.unknownType());
     *</pre>
     *<p>
     * This method should only be used if parameterization is completely unavailable.
     */
    public MapType constructRawMapType(Class<? extends Map> mapClass) {
        return constructMapType(mapClass, unknownType(), unknownType());
    }

    /**
     * Method that can be used to construct "raw" Map-like type; meaning that its
     * parameterization is unknown.
     * This is similar to using <code>Object.class</code> parameterization,
     * and is equivalent to calling:
     *<pre>
     *  typeFactory.constructMapLikeType(collectionClass, typeFactory.unknownType(), typeFactory.unknownType());
     *</pre>
     *<p>
     * This method should only be used if parameterization is completely unavailable.
     */
    public MapLikeType constructRawMapLikeType(Class<?> mapClass) {
        return constructMapLikeType(mapClass, unknownType(), unknownType());
    }

    /*
    /**********************************************************
    /* Low-level factory methods
    /**********************************************************
     */

    private JavaType _mapType(Class<?> rawClass, TypeBindings bindings,
            JavaType superClass, JavaType[] superInterfaces)
    {
        JavaType kt, vt;
        
        // 28-May-2015, tatu: Properties are special, as per [databind#810]; fake "correct" parameter sig
        if (rawClass == Properties.class) {
            kt = vt = CORE_TYPE_STRING;
        } else {
            List<JavaType> typeParams = bindings.getTypeParameters();
            // ok to have no types ("raw")
            switch (typeParams.size()) {
            case 0: // acceptable?
                kt = vt = _unknownType();
                break;
            case 2:
                kt = typeParams.get(0);
                vt = typeParams.get(1);
                break;
            default:
                throw new IllegalArgumentException("Strange Map type "+rawClass.getName()+": can not determine type parameters");
            }
        }
        return MapType.construct(rawClass, bindings, superClass, superInterfaces, kt, vt);
    }

    private JavaType _collectionType(Class<?> rawClass, TypeBindings bindings,
            JavaType superClass, JavaType[] superInterfaces)
    {
        List<JavaType> typeParams = bindings.getTypeParameters();
        // ok to have no types ("raw")
        JavaType ct;
        if (typeParams.isEmpty()) {
            ct = _unknownType();
        } else if (typeParams.size() == 1) {
            ct = typeParams.get(0);
        } else {
            throw new IllegalArgumentException("Strange Collection type "+rawClass.getName()+": can not determine type parameters");
        }
        return CollectionType.construct(rawClass, bindings, superClass, superInterfaces, ct);
    }

    private JavaType _referenceType(Class<?> rawClass, TypeBindings bindings,
            JavaType superClass, JavaType[] superInterfaces)
    {
        List<JavaType> typeParams = bindings.getTypeParameters();
        // ok to have no types ("raw")
        JavaType ct;
        if (typeParams.isEmpty()) {
            ct = _unknownType();
        } else if (typeParams.size() == 1) {
            ct = typeParams.get(0);
        } else {
            throw new IllegalArgumentException("Strange Reference type "+rawClass.getName()+": can not determine type parameters");
        }
        return ReferenceType.construct(rawClass, bindings, superClass, superInterfaces, ct);
    }

    /**
     * Factory method to call when no special {@link JavaType} is needed,
     * no generic parameters are passed. Default implementation may check
     * pre-constructed values for "well-known" types, but if none found
     * will simply call {@link #_newSimpleType}
     *
     * @since 2.7
     */
    protected JavaType _constructSimple(Class<?> raw, TypeBindings bindings,
            JavaType superClass, JavaType[] superInterfaces)
    {
        if (bindings.isEmpty()) {
            JavaType result = _findWellKnownSimple(raw);
            if (result != null) {
                return result;
            }
        }
        return _newSimpleType(raw, bindings, superClass, superInterfaces);
    }

    /**
     * Factory method that is to create a new {@link SimpleType} with no
     * checks whatsoever. Default implementation calls the single argument
     * constructor of {@link SimpleType}.
     *
     * @since 2.7
     */
    protected JavaType _newSimpleType(Class<?> raw, TypeBindings bindings,
            JavaType superClass, JavaType[] superInterfaces)
    {
        return new SimpleType(raw, bindings, superClass, superInterfaces);
    }

    protected JavaType _unknownType() {
        /* 15-Sep-2015, tatu: Prior to 2.7, we constructed new instance for each call.
         *    This may have been due to potential mutability of the instance; but that
         *    should not be issue any more, and creation is somewhat wasteful. So let's
         *    try reusing singleton/flyweight instance.
         */
        return CORE_TYPE_OBJECT;
    }

    /**
     * Helper method called to see if requested, non-generic-parameterized
     * type is one of common, "well-known" types, instances of which are
     * pre-constructed and do not need dynamic caching.
     *
     * @since 2.7
     */
    protected JavaType _findWellKnownSimple(Class<?> clz) {
        if (clz.isPrimitive()) {
            if (clz == CLS_BOOL) return CORE_TYPE_BOOL;
            if (clz == CLS_INT) return CORE_TYPE_INT;
            if (clz == CLS_LONG) return CORE_TYPE_LONG;
        } else {
            if (clz == CLS_STRING) return CORE_TYPE_STRING;
            if (clz == CLS_OBJECT) return CORE_TYPE_OBJECT; // since 2.7
        }
        return null;
    }

    /*
    /**********************************************************
    /* Actual type resolution, traversal
    /**********************************************************
     */

    /**
     * Factory method that can be used if type information is passed
     * as Java typing returned from <code>getGenericXxx</code> methods
     * (usually for a return or argument type).
     */
    protected JavaType _fromAny(ClassStack context, Type type, TypeBindings bindings)
    {
        JavaType resultType;

        // simple class?
        if (type instanceof Class<?>) {
            // Important: remove possible bindings since this is type-erased thingy
            resultType = _fromClass(context, (Class<?>) type, EMPTY_BINDINGS);
        }
        // But if not, need to start resolving.
        else if (type instanceof ParameterizedType) {
            resultType = _fromParamType(context, (ParameterizedType) type, bindings);
        }
        else if (type instanceof JavaType) { // [databind#116]
            // no need to modify further if we already had JavaType
            return (JavaType) type;
        }
        else if (type instanceof GenericArrayType) {
            resultType = _fromArrayType(context, (GenericArrayType) type, bindings);
        }
        else if (type instanceof TypeVariable<?>) {
            resultType = _fromVariable(context, (TypeVariable<?>) type, bindings);
        }
        else if (type instanceof WildcardType) {
            resultType = _fromWildcard(context, (WildcardType) type, bindings);
        } else {
            // sanity check
            throw new IllegalArgumentException("Unrecognized Type: "+((type == null) ? "[null]" : type.toString()));
        }
        /* Need to allow TypeModifiers to alter actual type; however,
         * for now only call for simple types (i.e. not for arrays, map or collections).
         * Can be changed in future it necessary
         */
        if (_modifiers != null && !resultType.isContainerType()) {
            TypeBindings b = resultType.getBindings();
            if (b == null) {
                b = EMPTY_BINDINGS;
            }
            for (TypeModifier mod : _modifiers) {
                resultType = mod.modifyType(resultType, type, b, this);
            }
        }
        return resultType;
    }

    /**
     * @param bindings Mapping of formal parameter declarations (for generic
     *   types) into actual types
     */
    protected JavaType _fromClass(ClassStack context, Class<?> rawType, TypeBindings bindings)
    {
        // Very first thing: small set of core types we know well:
        JavaType result = _findWellKnownSimple(rawType);
        if (result != null) {
            return result;
        }
        // Barring that, we may have recently constructed an instance:
        ClassKey key;

        // !!! TODO 16-Oct-2015, tatu: For now let's only cached non-parameterized; otherwise
        //     need better cache key
        if ((bindings == null) || bindings.isEmpty()) { 
            key = new ClassKey(rawType);
            result = _typeCache.get(key); // ok, cache object is synced
            if (result != null) {
                return result;
            }
        } else {
            key = null;
        }

        // 15-Oct-2015, tatu: recursive reference?
        if (context == null) {
            context = new ClassStack(rawType);
        } else {
            ClassStack prev = context.find(rawType);
            if (prev != null) {
                // Self-reference: needs special handling, then...
                ResolvedRecursiveType selfRef = new ResolvedRecursiveType(rawType, EMPTY_BINDINGS);
                prev.addSelfReference(selfRef);
                return selfRef;
            }
            // no, but need to update context to allow for proper cycle resolution
            context = context.child(rawType);
        }

        // First: do we have an array type?
        if (rawType.isArray()) {
            result = ArrayType.construct(_fromAny(context, rawType.getComponentType(), bindings),
                    bindings);
        } else {
            // If not, need to proceed by first resolving parent type hierarchy
            
            JavaType superClass;
            JavaType[] superInterfaces;

            if (rawType.isInterface()) {
                superClass = null;
                superInterfaces = _resolveSuperInterfaces(context, rawType, bindings);
            } else if (rawType.isEnum()) {
                // One simplification: let's not resolve interfaces, just super-classes (if any)
                superClass = _resolveSuperClass(context, rawType, bindings);
                superInterfaces = NO_TYPES;
            } else {
                superClass = _resolveSuperClass(context, rawType, bindings);
                superInterfaces = _resolveSuperInterfaces(context, rawType, bindings);
            }

            // 19-Oct-2015, tatu: Bit messy, but we need to 'fix' java.util.Properties here...
            if (rawType == Properties.class) {
                result = MapType.construct(rawType, bindings, superClass, superInterfaces,
                        CORE_TYPE_STRING, CORE_TYPE_STRING);
            }
            // And then check what flavor of type we got. Start by asking resolved
            // super-type if refinement is all that is needed?
            else if (superClass != null) {
                result = superClass.refine(rawType, bindings, superClass, superInterfaces);
            }
            // if not, perhaps we are now resolving a well-known class or interface?
            if (result == null) {
                result = _fromWellKnownClass(context, rawType, bindings, superClass, superInterfaces); 
                if (result == null) {
                    result = _fromWellKnownInterface(context, rawType, bindings, superClass, superInterfaces);
                    if (result == null) {
                        // but if nothing else, "simple" class for now:
                        result = _newSimpleType(rawType, bindings, superClass, superInterfaces);
                    }
                }
            }
        }
        context.resolveSelfReferences(result);

        if (key != null) {
            _typeCache.putIfAbsent(key, result); // cache object syncs
        }
        return result;
    }

    protected JavaType _resolveSuperClass(ClassStack context, Class<?> rawType, TypeBindings parentBindings)
    {
        Type parent = rawType.getGenericSuperclass();
        if (parent == null) {
            return null;
        }
        return _fromAny(context, parent, parentBindings);
    }

    protected JavaType[] _resolveSuperInterfaces(ClassStack context, Class<?> rawType, TypeBindings parentBindings)
    {
        Type[] types = rawType.getGenericInterfaces();
        if (types == null || types.length == 0) {
            return NO_TYPES;
        }
        int len = types.length;
        JavaType[] resolved = new JavaType[len];
        for (int i = 0; i < len; ++i) {
            Type type = types[i];
            resolved[i] = _fromAny(context, type, parentBindings);
        }
        return resolved;
    }

    /**
     * Helper class used to check whether exact class for which type is being constructed
     * is one of well-known base interfaces or classes that indicates alternate
     * {@link JavaType} implementation.
     */
    protected JavaType _fromWellKnownClass(ClassStack context, Class<?> rawType, TypeBindings bindings,
            JavaType superClass, JavaType[] superInterfaces)
    {
        // Quite simple when we resolving exact class/interface; start with that
        if (rawType == Map.class) {
            return _mapType(rawType, bindings, superClass, superInterfaces);
        }
        if (rawType == Collection.class) {
            return _collectionType(rawType, bindings, superClass, superInterfaces);
        }
        if (rawType == AtomicReference.class) {
            return _referenceType(rawType, bindings, superClass, superInterfaces);
        }
        return null;
    }

    protected JavaType _fromWellKnownInterface(ClassStack context, Class<?> rawType, TypeBindings bindings,
            JavaType superClass, JavaType[] superInterfaces)
    {
        // But that's not all: may be possible current type actually implements an
        // interface type. So...
        final int intCount = superInterfaces.length;

        for (int i = 0; i < intCount; ++i) {
            JavaType result = superInterfaces[i].refine(rawType, bindings, superClass, superInterfaces);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * This method deals with parameterized types, that is,
     * first class generic classes.
     */
    protected JavaType _fromParamType(ClassStack context, ParameterizedType ptype,
            TypeBindings parentBindings)
    {
        // 20-Oct-2015, tatu: Assumption here is we'll always get Class, not one of other Types
        Class<?> rawType = (Class<?>) ptype.getRawType();

        // First: what is the actual base type? One odd thing is that 'getRawType'
        // returns Type, not Class<?> as one might expect. But let's assume it is
        // always of type Class: if not, need to add more code to resolve it to Class.        
        Type[] args = ptype.getActualTypeArguments();
        int paramCount = (args == null) ? 0 : args.length;
        JavaType[] pt;
        TypeBindings newBindings;        

        if (paramCount == 0) {
            newBindings = EMPTY_BINDINGS;
        } else {
            pt = new JavaType[paramCount];
            for (int i = 0; i < paramCount; ++i) {
                pt[i] = _fromAny(context, args[i], parentBindings);
            }
            newBindings = TypeBindings.create(rawType, pt);
        }
        return _fromClass(context, rawType, newBindings);
    }

    protected JavaType _fromArrayType(ClassStack context, GenericArrayType type, TypeBindings bindings)
    {
        JavaType elementType = _fromAny(context, type.getGenericComponentType(), bindings);
        return ArrayType.construct(elementType, bindings);
    }

    protected JavaType _fromVariable(ClassStack context, TypeVariable<?> var, TypeBindings bindings)
    {
        // ideally should find it via bindings:
        final String name = var.getName();
        JavaType type = bindings.findBoundType(name);
        if (type != null) {
            return type;
        }
        // but if not, use bounds... note that approach here is simplistic; not taking
        // into account possible multiple bounds, nor consider upper bounds.
        if (bindings.hasUnbound(name)) {
            return CORE_TYPE_OBJECT;
        }
        bindings = bindings.withUnboundVariable(name);

        Type[] bounds = var.getBounds();
        return _fromAny(context, bounds[0], bindings);
    }

    protected JavaType _fromWildcard(ClassStack context, WildcardType type, TypeBindings bindings)
    {
        /* Similar to challenges with TypeVariable, we may have multiple upper bounds.
         * But it is also possible that if upper bound defaults to Object, we might
         * want to consider lower bounds instead.
         * For now, we won't try anything more advanced; above is just for future reference.
         */
        return _fromAny(context, type.getUpperBounds()[0], bindings);
    }
}
