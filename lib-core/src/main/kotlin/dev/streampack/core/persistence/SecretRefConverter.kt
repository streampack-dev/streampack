/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.persistence

import com.enigmastation.streampack.core.model.SecretRef
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/** JPA converter for persisted secret references (`literal` or `env://KEY`). */
@Converter(autoApply = false)
class SecretRefConverter : AttributeConverter<SecretRef, String> {
    override fun convertToDatabaseColumn(attribute: SecretRef?): String? =
        attribute?.asStoredValue()

    override fun convertToEntityAttribute(dbData: String?): SecretRef? =
        dbData?.let { SecretRef.parse(it) }
}
