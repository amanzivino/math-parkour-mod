package dev.mathparkour;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** A single prompt is one independently scheduled FSRS card. */
public record MathQuestion(
        String id,
        String prompt,
        String hint,
        int answer,
        boolean division,
        int operand1,
        int operand2) {

    public static List<MathQuestion> catalog() {
        Map<String, MathQuestion> questions = new LinkedHashMap<>();

        // Extended multiplication prompts: 2..15
        for (int a = 2; a <= 15; a++) {
            for (int b = 2; b <= 15; b++) {
                int product = a * b;
                String mulId = "mul:" + a + ":" + b;
                questions.putIfAbsent(mulId, new MathQuestion(
                        mulId,
                        a + " × " + b + " = ?",
                        "Picture " + a + " rows with " + b + " blocks in each row.",
                        product,
                        false,
                        a,
                        b));

                addDivision(questions, product, a, b);
                addDivision(questions, product, b, a);
            }
        }

        // Perfect squares as separate retrieval cards (2^2 .. 15^2).
        for (int n = 2; n <= 15; n++) {
            String id = "sq:" + n;
            questions.putIfAbsent(id, new MathQuestion(
                    id,
                    n + "² = ?",
                    "Think of " + n + " groups of " + n + ".",
                    n * n,
                    false,
                    n,
                    n));
        }

        // Science facts (simple numeric facts suitable for three-choice UI).
        addScienceFact(questions, "sci:water_boils", "Water boils at ? °C.", "A pot of water on the stove boils at 100°C.", 100);
        addScienceFact(questions, "sci:earth_hours", "Earth rotates once in ? hours.", "A day is 24 hours.", 24);
        addScienceFact(questions, "sci:seconds_minute", "A minute has ? seconds.", "60 seconds in a minute.", 60);
        addScienceFact(questions, "sci:moon_count", "Earth has ? natural moon(s).", "Earth has one moon.", 1);
        addScienceFact(questions, "sci:planet_count", "There are ? planets in the Solar System.", "Eight planets orbit the Sun.", 8);
        addScienceFact(questions, "sci:water_freezes", "Water freezes at ? °C.", "Ice forms at 0°C.", 0);
        addScienceFact(questions, "sci:oxygen_percent", "Approx. ?% of air is oxygen.", "About 21% of Earth's atmosphere is oxygen.", 21);
        addScienceFact(questions, "sci:nitrogen_percent", "Approx. ?% of air is nitrogen.", "About 78% of Earth's atmosphere is nitrogen.", 78);

        return List.copyOf(questions.values());
    }

    private static void addScienceFact(Map<String, MathQuestion> questions, String id, String prompt, String hint, int answer) {
        questions.putIfAbsent(id, new MathQuestion(
                id,
                prompt,
                hint,
                answer,
                false,
                0,
                0));
    }

    private static void addDivision(
            Map<String, MathQuestion> questions, int product, int divisor, int answer) {
        String id = "div:" + product + ":" + divisor;
        questions.putIfAbsent(id, new MathQuestion(
                id,
                product + " ÷ " + divisor + " = ?",
                "Reverse the multiplication: ? × " + divisor + " = " + product + ".",
                answer,
                true,
                product,
                divisor));
    }

    public static List<Integer> makeOptions(MathQuestion question, Random random) {
        Set<Integer> options = new LinkedHashSet<>();
        options.add(question.answer());

        List<Integer> candidates = new ArrayList<>();
        int answer = question.answer();
        if (question.division()) {
            // plausible divisors and near-misses
            for (int n = 2; n <= 15; n++) if (n != answer) candidates.add(n);
            candidates.add(answer - 1);
            candidates.add(answer + 1);
        } else {
            // arithmetic and science-friendly distractors
            candidates.add(answer - 1);
            candidates.add(answer + 1);
            candidates.add(answer - 2);
            candidates.add(answer + 2);
            candidates.add(answer + (question.operand1() == 0 ? 3 : question.operand1()));
            candidates.add(answer - (question.operand2() == 0 ? 2 : question.operand2()));
            candidates.add(answer + 10);
            candidates.add(answer - 10);
            for (int n = 2; n <= Math.max(100, answer + 30); n++) candidates.add(n);
        }
        candidates.removeIf(n -> n < 0 || n == answer || (question.division() && n > 15));
        Collections.shuffle(candidates, random);
        for (int candidate : candidates) {
            options.add(candidate);
            if (options.size() == 3) break;
        }
        while (options.size() < 3) {
            int candidate = question.division()
                    ? 2 + random.nextInt(14)
                    : Math.max(0, answer + random.nextInt(41) - 20);
            options.add(candidate);
        }

        List<Integer> result = new ArrayList<>(options);
        Collections.shuffle(result, random);
        return List.copyOf(result);
    }
}
