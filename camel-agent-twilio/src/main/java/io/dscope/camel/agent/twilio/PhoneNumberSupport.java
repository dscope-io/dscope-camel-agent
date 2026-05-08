package io.dscope.camel.agent.twilio;

final class PhoneNumberSupport {

    private PhoneNumberSupport() {
    }

    static String normalizePhone(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9+]", "");
        if (digits.startsWith("00")) {
            digits = "+" + digits.substring(2);
        }

        if (!digits.startsWith("+") && !digits.isBlank()) {
            String numericDigits = digits.replaceAll("[^0-9]", "");
            if (numericDigits.length() == 10) {
                digits = "+1" + numericDigits;
            } else {
                digits = "+" + numericDigits;
            }
        }

        return trimToNull(digits == null ? null : digits.replaceAll("(?!^)\\+", ""));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}