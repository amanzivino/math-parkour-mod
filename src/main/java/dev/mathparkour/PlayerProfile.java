package dev.mathparkour;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Data saved locally per player and per Minecraft world. */
public final class PlayerProfile {
    public Map<String, String> cards = new HashMap<>();
    public int correctChoices = 0;
    public int wrongChoices = 0;
    public int firstTryCorrect = 0;
    public int firstTryWrong = 0;
    public int fsrsReviews = 0;
    public int coursesCompleted = 0;

    // New currency + owned items
    public int points = 0;
    public Set<String> ownedItems = new HashSet<>();

    public PlayerProfile() {
        // Required by Gson.
    }

    public void normalize() {
        if (cards == null) cards = new HashMap<>();
        if (ownedItems == null) ownedItems = new HashSet<>();
    }
}
