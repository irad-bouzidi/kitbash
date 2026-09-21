package dev.kitbash.verify.bump;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comparing two version strings, and deciding which upgrades are the job's business.
 *
 * <p>Deliberately narrow. §36's job is to stop the catalog rotting, not to chase every release: a
 * pre-release, a release candidate or a milestone is something a person opts into, and a job that
 * proposed {@code 4.0.0-M2} every Monday would be turned off by the third Monday.
 */
public final class Versions {

    private static final Pattern NUMERIC = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(.*)$");

    private Versions() {}

    /**
     * Whether a candidate is a release at all.
     *
     * <p>A suffix that is not empty and not a plain build number means somebody is still deciding:
     * {@code -RC1}, {@code -M3}, {@code -alpha}, {@code -SNAPSHOT}. {@code .Final} is the exception
     * the JVM ecosystem insists on, and it means the opposite of the others.
     */
    public static boolean isRelease(String version) {
        Matcher matcher = NUMERIC.matcher(version);
        if (!matcher.matches()) {
            return false;
        }
        String suffix = matcher.group(4);
        return suffix.isEmpty() || suffix.equalsIgnoreCase(".final") || suffix.matches("\\.\\d+");
    }

    /** Newest first, so "the latest release" is the first element that {@link #isRelease} accepts. */
    public static List<String> releasesNewestFirst(List<String> versions) {
        return versions.stream()
                .filter(Versions::isRelease)
                .sorted(comparator().reversed())
                .toList();
    }

    /** Numeric where it can be, lexicographic where it cannot, so `10` sorts above `9`. */
    public static Comparator<String> comparator() {
        return (left, right) -> {
            String[] leftParts = left.split("[.-]");
            String[] rightParts = right.split("[.-]");
            for (int i = 0; i < Math.max(leftParts.length, rightParts.length); i++) {
                String a = i < leftParts.length ? leftParts[i] : "0";
                String b = i < rightParts.length ? rightParts[i] : "0";
                int compared = a.matches("\\d+") && b.matches("\\d+")
                        ? Integer.compare(Integer.parseInt(a), Integer.parseInt(b))
                        : a.compareTo(b);
                if (compared != 0) {
                    return compared;
                }
            }
            return 0;
        };
    }

    public static boolean isNewer(String candidate, String current) {
        return comparator().compare(candidate, current) > 0;
    }

    /**
     * The newest release that keeps the same major version.
     *
     * <p>The policy that makes a weekly job sustainable. A major upgrade is a decision — Spring
     * Boot 3 to 4 moves packages, Flyway 11 to 13 changes defaults — and a job that proposed one
     * every Monday would be red every Monday, which is the same as being off. Patches and minors
     * are the rot §12 is worried about, and those a job can carry.
     *
     * <p>Majors are not hidden: {@link #majorUpgrade} finds them, and the weekly summary lists
     * them as available rather than proposed, so the decision is offered rather than made.
     */
    public static String latestWithinMajor(String current, List<String> candidates) {
        String major = major(current);
        return releasesNewestFirst(candidates).stream()
                .filter(candidate -> major(candidate).equals(major))
                .filter(candidate -> isNewer(candidate, current))
                .findFirst()
                .orElse(null);
    }

    /** The newest release in a later major, or null when there is none. */
    public static String majorUpgrade(String current, List<String> candidates) {
        String major = major(current);
        return releasesNewestFirst(candidates).stream()
                .filter(candidate -> !major(candidate).equals(major))
                .filter(candidate -> isNewer(candidate, current))
                .findFirst()
                .orElse(null);
    }

    private static String major(String version) {
        int dot = version.indexOf('.');
        return dot < 0 ? version : version.substring(0, dot);
    }
}
