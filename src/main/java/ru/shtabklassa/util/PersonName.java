package ru.shtabklassa.util;

// не склоняем, только именительный - иначе будет "от Анна Сергеевны"
public final class PersonName {

    private PersonName() {
    }

    // ФИО -> имя отчество, иначе просто имя
    public static String address(String fullName) {
        String[] parts = fullName.strip().split("\\s+");
        return parts.length >= 3 ? parts[0] + " " + parts[1] : parts[0];
    }

    public static String first(String fullName) {
        return fullName.strip().split("\\s+")[0];
    }
}
