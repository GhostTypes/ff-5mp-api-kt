# Consumer ProGuard rules shipped to apps that depend on this library.
# kotlinx.serialization keeps its own generated serializers; @Serializable models
# are referenced reflectively at the boundary, so keep their metadata.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
