package com.navix.common.pii;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** {@code @Convert(converter = PiiConverter.class)} on an Aadhaar, PAN or account number column (PLAN Phase 9). */
@Converter
public class PiiConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String plain) {
        return plain == null ? null : PiiCipher.get().encrypt(plain);
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        return stored == null ? null : PiiCipher.get().decrypt(stored);
    }
}
