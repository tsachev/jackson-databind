package com.fasterxml.jackson.databind.jsontype;

import java.util.Collection;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.annotation.*;

import com.fasterxml.jackson.databind.*;

import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.jsontype.impl.StdTypeResolverBuilder;

/**
 * Abstraction used for allowing construction and registration of custom
 * {@link TypeResolverBuilder}s.
 * At this point contains both API and default implementation.
 *
 * @since 3.0
 */
public class TypeResolverProvider
{
    /*
    /**********************************************************************
    /* Public API
    /**********************************************************************
     */
    /**
     * Method for checking if given class has annotations that indicate
     * that specific type resolver is to be used for handling instances.
     * This includes not only
     * instantiating resolver builder, but also configuring it based on
     * relevant annotations (not including ones checked with a call to
     * {@link #findSubtypes}
     *
     * @param config Configuration settings in effect (for serialization or deserialization)
     * @param baseType Base java type of value for which resolver is to be found
     * 
     * @return Type resolver builder for given type, if one found; null if none
     */
    public TypeSerializer findTypeSerializer(SerializationConfig config,
            AnnotatedClass classInfo, JavaType baseType)
        throws JsonMappingException
    {
        final AnnotationIntrospector ai = config.getAnnotationIntrospector();
        JsonTypeInfo.Value typeInfo = ai.findPolymorphicTypeInfo(config, classInfo);
        TypeResolverBuilder<?> b = _findTypeResolver(config, classInfo, baseType, typeInfo);
        // Ok: if there is no explicit type info handler, we may want to
        // use a default. If so, config object knows what to use.
        Collection<NamedType> subtypes = null;
        if (b == null) {
            b = config.getDefaultTyper(baseType);
        } else {
            subtypes = config.getSubtypeResolver().collectAndResolveSubtypesByClass(config, classInfo);
        }
        if (b == null) {
            return null;
        }
        // 10-Jun-2015, tatu: Since not created for Bean Property, no need for post-processing
        //    wrt EXTERNAL_PROPERTY
        return b.buildTypeSerializer(config, baseType, subtypes);
    }

    /**
     * @param abstractDefaultMapper (optional) If caller can provide mapping for abstract base type to
     *    concrete, this is the accessor to call with base type
     */
    public TypeDeserializer findTypeDeserializer(DeserializationConfig config,
            AnnotatedClass classInfo, JavaType baseType,
            UnaryOperator<JavaType> abstractDefaultMapper)
        throws JsonMappingException
    {
        final AnnotationIntrospector ai = config.getAnnotationIntrospector();
        JsonTypeInfo.Value typeInfo = ai.findPolymorphicTypeInfo(config, classInfo);
        TypeResolverBuilder<?> b = _findTypeResolver(config,
                classInfo, baseType, typeInfo);

        // Ok: if there is no explicit type info handler, we may want to
        // use a default. If so, config object knows what to use.
        Collection<NamedType> subtypes = null;
        if (b == null) {
            b = config.getDefaultTyper(baseType);
            if (b == null) {
                return null;
            }
        } else {
            subtypes = config.getSubtypeResolver().collectAndResolveSubtypesByTypeId(config, classInfo);
        }
        // May need to figure out default implementation, if none found yet
        // (note: check for abstract type is not 100% mandatory, more of an optimization)
        if ((abstractDefaultMapper != null)
                && (b.getDefaultImpl() == null) && baseType.isAbstract()) {
//            JavaType defaultType = mapAbstractType(config, baseType);
            JavaType defaultType = abstractDefaultMapper.apply(baseType);

            if ((defaultType != null) && !defaultType.hasRawClass(baseType.getRawClass())) {
                b = b.defaultImpl(defaultType.getRawClass());
            }
        }
        return b.buildTypeDeserializer(config, baseType, subtypes);
    }
    
    /*
    /**********************************************************************
    /* Helper methods
    /**********************************************************************
     */

    protected TypeResolverBuilder<?> _findTypeResolver(MapperConfig<?> config,
            Annotated ann, JavaType baseType, JsonTypeInfo.Value typeInfo)
    {
        final AnnotationIntrospector ai = config.getAnnotationIntrospector();
        // First: maybe we have explicit type resolver?
        TypeResolverBuilder<?> b;
        Object customResolverOb = ai.findTypeResolverBuilder(config, ann);
        if (customResolverOb != null) {
            // 08-Mar-2018, tatu: Should `NONE` block custom one? Or not?
            if ((typeInfo != null) && (typeInfo.getIdType() == JsonTypeInfo.Id.NONE)) {
                return null;
            }
            if (customResolverOb instanceof Class<?>) {
                @SuppressWarnings("unchecked")
                Class<TypeResolverBuilder<?>> cls = (Class<TypeResolverBuilder<?>>) customResolverOb;
                b = config.typeResolverBuilderInstance(ann, cls);
            } else {
                b = (TypeResolverBuilder<?>) customResolverOb;
            }
        } else { // if not, use standard one, but only if indicated by annotations
            if (typeInfo == null) {
                return null;
            }
            // bit special; must return 'marker' to block use of default typing:
            if (typeInfo.getIdType() == JsonTypeInfo.Id.NONE) {
                return _constructNoTypeResolverBuilder();
            }
            // 13-Aug-2011, tatu: One complication; external id
            //   only works for properties; so if declared for a Class, we will need
            //   to map it to "PROPERTY" instead of "EXTERNAL_PROPERTY"
            JsonTypeInfo.As inclusion = typeInfo.getInclusionType();
            if (inclusion == JsonTypeInfo.As.EXTERNAL_PROPERTY && (ann instanceof AnnotatedClass)) {
                typeInfo = typeInfo.withInclusionType(JsonTypeInfo.As.PROPERTY);
            }
            b = _constructStdTypeResolverBuilder(typeInfo);
        }
        // Does it define a custom type id resolver?
        Object customIdResolverOb = ai.findTypeIdResolver(config, ann);
        TypeIdResolver idResolver = null;

        if (customIdResolverOb != null) {
            if (customIdResolverOb instanceof Class<?>) {
                @SuppressWarnings("unchecked")
                Class<TypeIdResolver> cls = (Class<TypeIdResolver>) customIdResolverOb;
                idResolver = config.typeIdResolverInstance(ann, cls);
                idResolver.init(baseType);
            }
        }
        b = b.init(typeInfo, idResolver);
        return b;
    }

    protected StdTypeResolverBuilder _constructStdTypeResolverBuilder(JsonTypeInfo.Value typeInfo) {
        return new StdTypeResolverBuilder(typeInfo);
    }

    /**
     * Helper method for dealing with "no type info" marker; can't be null
     * (as it'd be replaced by default typing)
     */
    protected StdTypeResolverBuilder _constructNoTypeResolverBuilder() {
        return StdTypeResolverBuilder.noTypeInfoBuilder();
    }
}
