/* Joseph B. Ottinger (C)2026 */
package dev.streampack.startrader.model

enum class Commodity(val tier: Int, val displayName: String) {
    ORE(1, "Ore"),
    ORGANICS(1, "Organics"),
    FUEL(1, "Fuel"),
    ALLOYS(2, "Alloys"),
    CHEMICALS(2, "Chemicals"),
    COMPONENTS(2, "Components"),
    MACHINES(3, "Machines"),
    TECH(3, "Tech"),
    MEDICINE(3, "Medicine"),
    TEXTILES(3, "Textiles"),
    FOOD(4, "Food"),
    LUXURY(4, "Luxury"),
}
