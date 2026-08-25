package de.tum.cit.aet.artemis.hyperion.mcq.readiness;

import java.util.List;

/**
 * The result of checking whether the tool can actually run.
 * <p>
 * Exists because every prerequisite failure this project has hit in practice surfaced as something
 * unhelpful: a missing embedding server as HTTP 500 on every page, an unreachable chat backend as a
 * multi-minute hang, an unset key as a 401 mid-run, an absent {@code data/} directory as
 * {@code SQLITE_CANTOPEN}. Each check therefore carries the fix, not just the verdict.
 */
public record Readiness(List<Check> checks) {

    /** How a single prerequisite stands. */
    public enum Status {

        /** Satisfied. */
        OK,

        /** Not satisfied, and nothing will work until it is. */
        BLOCKED,

        /** Usable, but something is worth knowing. */
        WARN
    }

    /**
     * @param name   short label, for example {@code "Embedding backend"}
     * @param status how it stands
     * @param detail what was found
     * @param fix    what to do about it, or {@code null} when nothing is needed
     */
    public record Check(String name, Status status, String detail, String fix) {

        public static Check ok(String name, String detail) {
            return new Check(name, Status.OK, detail, null);
        }

        public static Check blocked(String name, String detail, String fix) {
            return new Check(name, Status.BLOCKED, detail, fix);
        }

        public static Check warn(String name, String detail, String fix) {
            return new Check(name, Status.WARN, detail, fix);
        }
    }

    /** @return whether generation could run right now */
    public boolean ready() {
        return checks.stream().noneMatch(check -> check.status() == Status.BLOCKED);
    }

    /** @return the checks that must be fixed before anything works */
    public List<Check> blockers() {
        return checks.stream().filter(check -> check.status() == Status.BLOCKED).toList();
    }

    /** @return the checks worth knowing about but not blocking */
    public List<Check> warnings() {
        return checks.stream().filter(check -> check.status() == Status.WARN).toList();
    }
}
