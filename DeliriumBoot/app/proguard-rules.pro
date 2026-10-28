# DeliriumBoot — правила ProGuard/R8.
# Приложение не использует рефлексию в критичных местах, поэтому стандартных
# правил Android/Compose достаточно. Ниже — безопасные дополнения.

-keepattributes *Annotation*
-keep class me.jahnen.libaums.** { *; }
-dontwarn me.jahnen.libaums.**
