plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

dependencies {
    // AE2 API for 1.7.10 (dev classifier = deobfuscated, compile-time only, not bundled)
    compileOnly("com.github.GTNewHorizons:Applied-Energistics-2-Unofficial:rv3-beta-1053-GTNH:dev")
    // NEI is the 1.7.10 recipe viewer (GTNH ships no JEI). Only the client-side
    // drag/hide relay touches it, and that relay is loaded reflectively from
    // ClientProxy so the mod keeps working when NEI is not installed.
    compileOnly("com.github.GTNewHorizons:NotEnoughItems:2.8.44-GTNH:dev")
    // Referenced by AE2 classes on the compile classpath
    compileOnly("org.jetbrains:annotations:24.0.1")
}
