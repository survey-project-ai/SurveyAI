package com.yourcompany.surveyai.operation.support;

import java.util.regex.Pattern;

// Normalizes contact phone numbers to E.164 digits without the leading "+", with Turkey as the default country.
public final class PhoneNumberNormalizer {

    private static final String TURKEY_COUNTRY_CODE = "90";
    private static final int TURKEY_E164_LENGTH = 12;
    private static final Pattern E164_DIGITS_PATTERN = Pattern.compile("^[1-9]\\d{7,14}$");

    private PhoneNumberNormalizer() {
    }

    public static String normalize(String phoneNumber) {
        if (phoneNumber == null) {
            return "";
        }

        String trimmed = phoneNumber.trim();
        String digitsOnly = trimmed.replaceAll("\\D", "");

        if (trimmed.startsWith("+")) {
            return digitsOnly;
        }
        if (digitsOnly.startsWith("00")) {
            return digitsOnly.substring(2);
        }
        if (digitsOnly.startsWith("0")) {
            return TURKEY_COUNTRY_CODE + digitsOnly.substring(1);
        }
        // A Turkish number whose leading 0 was dropped, which Excel does for numeric cells
        // ("0532 123 45 67" becomes 5321234567). Kept as is, it would be dialed as +53 (Cuba).
        if (digitsOnly.length() == 10 && "2345".indexOf(digitsOnly.charAt(0)) >= 0) {
            return TURKEY_COUNTRY_CODE + digitsOnly;
        }
        return digitsOnly;
    }

    public static boolean isValid(String normalizedPhoneNumber) {
        if (normalizedPhoneNumber == null || !E164_DIGITS_PATTERN.matcher(normalizedPhoneNumber).matches()) {
            return false;
        }
        return !normalizedPhoneNumber.startsWith(TURKEY_COUNTRY_CODE) || normalizedPhoneNumber.length() == TURKEY_E164_LENGTH;
    }
}
