package dev.gitcommitforai.settings;

import com.intellij.credentialStore.*;
import com.intellij.ide.passwordSafe.PasswordSafe;
import java.util.Map;

public final class SecretStore {
    private SecretStore() {}
    private static CredentialAttributes attributes(String id) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName("git commit for ai", "model/" + id));
    }
    /** These methods must run on a background thread. */
    public static String read(String id) {
        String password = PasswordSafe.getInstance().getPassword(attributes(id));
        return password == null ? "" : password;
    }
    public static void save(Map<String, String> changes) {
        PasswordSafe store = PasswordSafe.getInstance();
        if (changes.values().stream().anyMatch(v -> !v.isEmpty()) && store.isMemoryOnly())
            throw new IllegalStateException("IDE 当前仅在内存保存密码。请先在 Settings → Appearance & Behavior → System Settings → Passwords 启用持久密码存储。");
        changes.forEach((id, key) -> store.setPassword(attributes(id), key.isEmpty() ? null : key));
    }
}

