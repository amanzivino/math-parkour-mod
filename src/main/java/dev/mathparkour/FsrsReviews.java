package dev.mathparkour;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.CardAndReviewLog;
import io.github.openspacedrepetition.Rating;
import io.github.openspacedrepetition.Scheduler;

import java.time.Duration;
import java.time.Instant;

/** Adapter around the existing Java FSRS scheduler. */
public final class FsrsReviews {
    private static final Scheduler SCHEDULER = Scheduler.builder()
            .desiredRetention(0.92)
            .learningSteps(new Duration[]{Duration.ofMinutes(1), Duration.ofMinutes(10)})
            .relearningSteps(new Duration[]{Duration.ofMinutes(10)})
            .maximumInterval(36500)
            .enableFuzzing(false)
            .build();

    private FsrsReviews() {}

    public static Card readCard(PlayerProfile profile, String questionId) {
        String json = profile.cards.get(questionId);
        if (json == null || json.isBlank()) return Card.builder().build();
        try {
            return Card.fromJson(json);
        } catch (RuntimeException e) {
            MathParkourMod.LOGGER.warn("Invalid FSRS card {}; creating a new card.", questionId, e);
            return Card.builder().build();
        }
    }

    public static Instant dueAt(PlayerProfile profile, String questionId) {
        String json = profile.cards.get(questionId);
        if (json == null || json.isBlank()) return Instant.EPOCH;
        try {
            return Card.fromJson(json).getDue();
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }

    public static void rate(PlayerProfile profile, String questionId, Rating rating) {
        Card card = readCard(profile, questionId);
        CardAndReviewLog result = SCHEDULER.reviewCard(card, rating);
        profile.cards.put(questionId, result.card().toJson());
        profile.fsrsReviews++;
    }

    public static int dueCount(PlayerProfile profile, Instant now) {
        int count = 0;
        for (String cardJson : profile.cards.values()) {
            try {
                if (!Card.fromJson(cardJson).getDue().isAfter(now)) count++;
            } catch (RuntimeException ignored) {
                count++;
            }
        }
        return count;
    }
}
