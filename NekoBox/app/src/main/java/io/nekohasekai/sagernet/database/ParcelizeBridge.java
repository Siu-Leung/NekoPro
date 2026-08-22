package io.nekohasekai.sagernet.database;

import android.os.Parcel;

/**
 * see: https://youtrack.jetbrains.com/issue/KT-19853
 * NOTE: Must stay as Java — Kotlin cannot reference the synthesized
 * parcelize CREATOR field (KT-19853).
 */
public class ParcelizeBridge {

    public static RuleEntity createRule(Parcel parcel) {
        return (RuleEntity) RuleEntity.CREATOR.createFromParcel(parcel);
    }
}
