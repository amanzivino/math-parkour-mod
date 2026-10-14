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

        return List.copyOf(questions.values());
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
            for (int n = 2; n <= 15; n++) {
                if (n != answer) candidates.add(n);
            }
            candidates.add(answer - 1);
            candidates.add(answer + 1);
        } else {
            candidates.add(answer - 1);
            candidates.add(answer + 1);
            candidates.add(answer - 2);
            candidates.add(answer + 2);
            candidates.add(answer + question.operand1());
            candidates.add(answer - question.operand2());
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
