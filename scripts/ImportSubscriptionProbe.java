import com.rayren.domain.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.TreeMap;

/** Read a response from stdin; keep credentials in memory and print counts only. */
class ImportSubscriptionProbe {
    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        byte[] input = System.in.readNBytes(2 * 1024 * 1024 + 1);
        if (input.length > 2 * 1024 * 1024) {
            System.out.println("IMPORT_REJECTED INPUT_TOO_LARGE");
            System.exit(2);
        }
        try {
            var result = ImportParser.INSTANCE.parse(ManifestVerifier.INSTANCE.payload(new String(input, StandardCharsets.UTF_8), ""));
            var counts = new TreeMap<String, Integer>();
            var listener = new Listener(31080, 31081, 31082, "fixture", "fixture-password", 31083);
            for (var profile : result.getProfiles()) {
                var config = PolicyCompiler.INSTANCE.compile(profile, new Preferences(), listener, Collections.emptyMap(), "1.1.1.1");
                Json.INSTANCE.parse(config);
                counts.merge(profile.getEngine().name() + "/" + profile.getProtocol(), 1, Integer::sum);
            }
            System.out.println("IMPORT_OK profiles=" + result.getProfiles().size() + " protocols=" + counts + " compiled=" + result.getProfiles().size());
        } catch (ValidationException e) {
            System.out.println("IMPORT_REJECTED " + e.getCode() + " " + Redaction.INSTANCE.sanitize(e.getReason()));
            System.exit(2);
        } catch (Throwable e) {
            System.out.println("IMPORT_ERROR " + e.getClass().getSimpleName());
            System.exit(2);
        }
    }
}
