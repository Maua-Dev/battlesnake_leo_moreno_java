package com.mauadev.code;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.mauadev.code.entities.Coordinate;
import com.mauadev.code.entities.GameState;
import com.mauadev.code.entities.Snake;

/** Estrategia para Battlesnake Standard. Compativel com Java 17. */
public class Logic {
    private static final String[] DIRECTIONS = {
        "up", "down", "left", "right"
    };

    private static final long WIN = 1_000_000_000L;
    private static final long INF = 1_000_000_000_000_000L;

    public static Map<String, String> info() {
        return Map.of(
            "apiversion", "1",
            "author", "",
            "color", "#FF4D00",
            "head", "caffeine",
            "tail", "hook",
            "version", "2.0.0"
        );
    }

    public static void start(GameState state) {}

    public static void end(GameState state) {}

    public static String getMove(GameState state) {
        if (
            state == null
            || state.getBoard() == null
            || state.getYou() == null
        ) {
            return "up";
        }

        int w = state.getBoard().getWidth();
        int h = state.getBoard().getHeight();

        if (w <= 0 || h <= 0 || (long) w * h > 2500) {
            return "up";
        }

        return DIRECTIONS[new Engine(state).choose()];
    }

    private record S(int[] body, int health) {}

    private record Position(
        S[] snakes,
        Set<Integer> food,
        int ply
    ) {}

    private record Ranked(
        boolean safe,
        long score,
        int direction
    ) {}

    private static final class Deadline extends RuntimeException {
        Deadline() {
            super(null, null, false, false);
        }
    }

    private static final class Engine {
        final int w;
        final int h;
        final int n;

        final int[][] neighbors;

        final Set<Integer> hazards = new HashSet<>();
        final Position initial;
        final boolean hadEnemies;
        final long deadline;

        Engine(GameState state) {
            w = state.getBoard().getWidth();
            h = state.getBoard().getHeight();
            n = w * h;

            neighbors = new int[n][4];

            for (int p = 0; p < n; p++) {
                int x = p % w;
                int y = p / w;

                neighbors[p] = new int[] {
                    y + 1 < h ? p + w : -1,
                    y > 0 ? p - w : -1,
                    x > 0 ? p - 1 : -1,
                    x + 1 < w ? p + 1 : -1
                };
            }

            // Game nao expoe ruleset: evita hazards como obstaculos.
            // Esta estrategia foi feita para as regras Standard.
            for (
                Coordinate c :
                list(state.getBoard().getHazards())
            ) {
                hazards.add(cell(c));
            }

            List<Snake> all = new ArrayList<>();
            all.add(state.getYou());

            for (Snake s : list(state.getBoard().getSnakes())) {
                if (
                    !Objects.equals(
                        s.getId(),
                        state.getYou().getId()
                    )
                ) {
                    all.add(s);
                }
            }

            S[] snakes = new S[all.size()];

            for (int i = 0; i < snakes.length; i++) {
                Snake s = all.get(i);
                List<Coordinate> body = list(s.getBody());

                int[] cells = body.isEmpty()
                    ? new int[] {cell(s.getHead())}
                    : body.stream()
                        .mapToInt(this::cell)
                        .toArray();

                snakes[i] = new S(cells, s.getHealth());
            }

            Set<Integer> food = new HashSet<>();

            for (Coordinate c : list(state.getBoard().getFood())) {
                if (cell(c) >= 0) {
                    food.add(cell(c));
                }
            }

            initial = new Position(snakes, food, 0);
            hadEnemies = snakes.length > 1;

            int timeout = state.getGame() == null
                ? 500
                : state.getGame().getTimeout();

            if (timeout <= 0) {
                timeout = 500;
            }

            // Reserva tempo para serializacao, Lambda e rede.
            long budgetMs = Math.max(
                0,
                Math.min(160, timeout - 180)
            );

            deadline = System.nanoTime()
                + budgetMs * 1_000_000L;
        }

        private static <T> List<T> list(List<T> items) {
            return items == null
                ? Collections.emptyList()
                : items;
        }

        int cell(Coordinate c) {
            if (
                c == null
                || c.getX() < 0
                || c.getY() < 0
                || c.getX() >= w
                || c.getY() >= h
            ) {
                return -1;
            }

            return c.getY() * w + c.getX();
        }

        boolean[] blocked(Position pos) {
            boolean[] occupied = new boolean[n];

            for (int p : hazards) {
                if (p >= 0) {
                    occupied[p] = true;
                }
            }

            // Remove apenas a ultima parte da cauda.
            // Uma cauda duplicada continua bloqueada.
            for (S s : pos.snakes) {
                if (s == null) {
                    continue;
                }

                for (int k = 0; k + 1 < s.body.length; k++) {
                    if (s.body[k] >= 0) {
                        occupied[s.body[k]] = true;
                    }
                }
            }

            return occupied;
        }

        List<Integer> legal(Position pos, int i) {
            S s = pos.snakes[i];
            List<Integer> moves = new ArrayList<>(4);

            if (s == null || s.body[0] < 0) {
                return moves;
            }

            boolean[] occupied = blocked(pos);

            int neck = s.body.length > 1
                ? s.body[1]
                : -1;

            for (int d = 0; d < 4; d++) {
                int p = neighbors[s.body[0]][d];

                if (
                    p >= 0
                    && !occupied[p]
                    && p != neck
                    && (s.health > 1 || pos.food.contains(p))
                ) {
                    moves.add(d);
                }
            }

            return moves;
        }

        int emergency(Position pos, int i) {
            S s = pos.snakes[i];

            if (s == null || s.body[0] < 0) {
                return 0;
            }

            boolean[] occupied = blocked(pos);

            int neck = s.body.length > 1
                ? s.body[1]
                : -1;

            int best = 0;
            int value = -1;

            for (int d = 0; d < 4; d++) {
                int p = neighbors[s.body[0]][d];

                int score = (p >= 0 ? 4 : 0)
                    + (p != neck ? 2 : 0)
                    + (p < 0 || !occupied[p] ? 1 : 0);

                if (score > value) {
                    best = d;
                    value = score;
                }
            }

            return best;
        }

        S advance(S s, int d, Set<Integer> food) {
            int head = s.body[0] >= 0
                ? neighbors[s.body[0]][d]
                : -1;

            boolean eats = food.contains(head);

            int[] body = new int[
                s.body.length + (eats ? 1 : 0)
            ];

            body[0] = head;

            System.arraycopy(
                s.body,
                0,
                body,
                1,
                s.body.length - 1
            );

            // No Standard, primeiro move e duplica a nova cauda.
            if (eats) {
                body[body.length - 1] = body[body.length - 2];
            }

            return new S(
                body,
                eats ? 100 : s.health - 1
            );
        }

        Position turn(Position pos, int[] moves) {
            S[] moved = new S[pos.snakes.length];
            boolean[] active = new boolean[moved.length];

            Set<Integer> food = new HashSet<>(pos.food);

            for (int i = 0; i < moved.length; i++) {
                if (pos.snakes[i] == null) {
                    continue;
                }

                moved[i] = advance(
                    pos.snakes[i],
                    moves[i],
                    pos.food
                );

                S s = moved[i];

                food.remove(s.body[0]);

                active[i] = (
                    s.health > 0
                    && s.body[0] >= 0
                    && !hazards.contains(s.body[0])
                );
            }

            boolean[] alive = active.clone();

            // Colisoes sao resolvidas simultaneamente.
            for (int i = 0; i < moved.length; i++) {
                if (!active[i]) {
                    continue;
                }

                S s = moved[i];

                for (int j = 0; j < moved.length; j++) {
                    if (!active[j]) {
                        continue;
                    }

                    S other = moved[j];
                    boolean hit = false;

                    for (int k = 1; k < other.body.length; k++) {
                        if (s.body[0] == other.body[k]) {
                            hit = true;
                            break;
                        }
                    }

                    boolean headCollision = (
                        i != j
                        && s.body[0] == other.body[0]
                        && s.body.length <= other.body.length
                    );

                    if (hit || headCollision) {
                        alive[i] = false;
                        break;
                    }
                }
            }

            for (int i = 0; i < moved.length; i++) {
                if (!alive[i]) {
                    moved[i] = null;
                }
            }

            return new Position(
                moved,
                food,
                pos.ply + 1
            );
        }

        int[] distances(
            int start,
            boolean[] occupied,
            int[] release
        ) {
            int[] dist = new int[n];
            int[] queue = new int[n];

            Arrays.fill(dist, n + 1);

            if (start < 0) {
                return dist;
            }

            int read = 0;
            int write = 0;

            queue[write++] = start;
            dist[start] = 0;

            while (read < write) {
                int p = queue[read++];
                int t = dist[p] + 1;

                for (int q : neighbors[p]) {
                    if (
                        q < 0
                        || hazards.contains(q)
                        || dist[q] <= t
                    ) {
                        continue;
                    }

                    if (
                        release == null
                            ? occupied[q]
                            : release[q] > t
                    ) {
                        continue;
                    }

                    dist[q] = t;
                    queue[write++] = q;
                }
            }

            return dist;
        }

        int count(int[] dist) {
            int result = 0;

            for (int d : dist) {
                if (d <= n) {
                    result++;
                }
            }

            return result;
        }

        boolean enemiesAlive(Position pos) {
            for (int i = 1; i < pos.snakes.length; i++) {
                if (pos.snakes[i] != null) {
                    return true;
                }
            }

            return false;
        }

        long evaluate(Position pos) {
            if (pos.snakes[0] == null) {
                return (
                    enemiesAlive(pos) ? -WIN : -WIN / 2
                ) + pos.ply * 1000L;
            }

            if (hadEnemies && !enemiesAlive(pos)) {
                return WIN - pos.ply * 1000L;
            }

            return heuristic(pos);
        }

        long heuristic(Position pos) {
            S me = pos.snakes[0];
            List<S> enemies = new ArrayList<>();

            for (int i = 1; i < pos.snakes.length; i++) {
                if (pos.snakes[i] != null) {
                    enemies.add(pos.snakes[i]);
                }
            }

            boolean[] occupied = blocked(pos);

            int[] mine = distances(
                me.body[0],
                occupied,
                null
            );

            List<int[]> theirs = new ArrayList<>();

            int length = me.body.length;
            int longest = enemies.isEmpty() ? length : 0;

            for (S s : enemies) {
                longest = Math.max(longest, s.body.length);

                theirs.add(
                    distances(s.body[0], occupied, null)
                );
            }

            int lead = length - longest;
            int space = count(mine);

            long score = (
                Math.min(4, Math.max(-8, lead)) * 2200L
                + Math.min(space, length * 2 + 8) * 35L
            );

            // Liberacao temporal e estimativa.
            // A busca simula os corpos reais.
            if (space < length + 2) {
                int[] release = new int[n];

                for (S s : pos.snakes) {
                    if (s == null) {
                        continue;
                    }

                    for (int k = 0; k < s.body.length; k++) {
                        if (s.body[k] >= 0) {
                            release[s.body[k]] = Math.max(
                                release[s.body[k]],
                                s.body.length - k
                            );
                        }
                    }
                }

                int dynamicSpace = count(
                    distances(
                        me.body[0],
                        occupied,
                        release
                    )
                );

                score -= Math.max(
                    0,
                    length + 2 - space
                ) * 1500L;

                if (dynamicSpace < length) {
                    score -= (
                        2_000_000L
                        + (length - dynamicSpace) * 10000L
                    );
                }
            }

            // Territorio: casas onde chegamos primeiro.
            for (int p = 0; p < n; p++) {
                if (mine[p] > n) {
                    continue;
                }

                boolean ours = true;

                for (int k = 0; k < enemies.size(); k++) {
                    int other = theirs.get(k)[p];

                    if (
                        !(
                            mine[p] < other
                            || (
                                mine[p] == other
                                && length > enemies.get(k).body.length
                            )
                        )
                    ) {
                        ours = false;
                        break;
                    }
                }

                if (ours) {
                    score += 12;
                }
            }

            int foodDistance = n + 1;

            for (int p : pos.food) {
                if (mine[p] > n) {
                    continue;
                }

                boolean contested = false;

                for (int k = 0; k < enemies.size(); k++) {
                    int other = theirs.get(k)[p];

                    if (
                        other < mine[p]
                        || (
                            other == mine[p]
                            && enemies.get(k).body.length >= length
                        )
                    ) {
                        contested = true;
                        break;
                    }
                }

                foodDistance = Math.min(
                    foodDistance,
                    mine[p] + (contested ? 5 : 0)
                );
            }

            int hunger = me.health <= 25
                ? 1100
                : me.health <= 55
                    ? 260
                    : lead < 3 ? 90 : 12;

            score -= Math.min(
                foodDistance,
                30
            ) * (long) hunger;

            score += me.health * 3L;

            if (
                !pos.food.isEmpty()
                && me.health <= 25
                && foodDistance >= me.health
            ) {
                score -= 1_000_000;
            }

            for (int k = 0; k < enemies.size(); k++) {
                S s = enemies.get(k);

                if (count(theirs.get(k)) < s.body.length) {
                    score += 3500;
                }

                // Aproxima-se quando temos vantagem e espaco.
                if (
                    lead > 0
                    && space >= length + 2
                    && me.health > 35
                ) {
                    int a = me.body[0];
                    int b = s.body[0];

                    score -= (
                        Math.abs(a % w - b % w)
                        + Math.abs(a / w - b / w)
                    ) * 25L;
                }
            }

            int p = me.body[0];

            // Preferencia pequena pelo centro para desempatar.
            score -= (
                Math.abs(2 * (p % w) - w + 1)
                + Math.abs(2 * (p / w) - h + 1)
            );

            return score;
        }

        List<Integer> rootOrder(Position pos) {
            List<Ranked> ranked = new ArrayList<>();

            for (int d : legal(pos, 0)) {
                S me = advance(
                    pos.snakes[0],
                    d,
                    pos.food
                );

                int head = me.body[0];
                boolean risk = false;

                for (int i = 1; i < pos.snakes.length; i++) {
                    S s = pos.snakes[i];

                    if (
                        s == null
                        || s.body.length
                            + (pos.food.contains(head) ? 1 : 0)
                            < me.body.length
                    ) {
                        continue;
                    }

                    for (int m : legal(pos, i)) {
                        if (neighbors[s.body[0]][m] == head) {
                            risk = true;
                            break;
                        }
                    }
                }

                S[] previewSnakes = pos.snakes.clone();
                previewSnakes[0] = me;

                Set<Integer> food = new HashSet<>(pos.food);
                food.remove(head);

                Position preview = new Position(
                    previewSnakes,
                    food,
                    pos.ply + 1
                );

                ranked.add(
                    new Ranked(
                        !risk,
                        heuristic(preview),
                        d
                    )
                );
            }

            boolean hasSafe = ranked.stream()
                .anyMatch(Ranked::safe);

            // Evita disputa fatal de cabecas se houver alternativa.
            ranked.removeIf(r -> hasSafe && !r.safe);

            ranked.sort(
                Comparator.comparingLong(Ranked::score)
                    .thenComparingInt(Ranked::direction)
                    .reversed()
            );

            List<Integer> order = new ArrayList<>();

            for (Ranked r : ranked) {
                order.add(r.direction);
            }

            return order;
        }

        void checkTime() {
            if (System.nanoTime() >= deadline) {
                throw new Deadline();
            }
        }

        long maximize(
            Position pos,
            int depth,
            long alpha,
            long beta
        ) {
            checkTime();

            if (
                depth == 0
                || pos.snakes[0] == null
                || (hadEnemies && !enemiesAlive(pos))
            ) {
                return evaluate(pos);
            }

            long best = -INF;
            List<Integer> moves = legal(pos, 0);

            if (moves.isEmpty()) {
                moves.add(emergency(pos, 0));
            }

            for (int d : moves) {
                best = Math.max(
                    best,
                    minimize(
                        pos,
                        d,
                        depth,
                        alpha,
                        beta
                    )
                );

                alpha = Math.max(alpha, best);

                if (alpha >= beta) {
                    break;
                }
            }

            return best;
        }

        long minimize(
            Position pos,
            int move,
            int depth,
            long alpha,
            long beta
        ) {
            List<List<Integer>> choices = new ArrayList<>();
            choices.add(List.of(move));

            for (int i = 1; i < pos.snakes.length; i++) {
                List<Integer> options = legal(pos, i);

                if (options.isEmpty()) {
                    options.add(emergency(pos, i));
                }

                choices.add(options);
            }

            int[] moves = new int[pos.snakes.length];
            moves[0] = move;

            return combinations(
                pos,
                choices,
                moves,
                1,
                depth,
                alpha,
                beta
            );
        }

        long combinations(
            Position pos,
            List<List<Integer>> choices,
            int[] moves,
            int i,
            int depth,
            long alpha,
            long beta
        ) {
            checkTime();

            if (i == moves.length) {
                return maximize(
                    turn(pos, moves),
                    depth - 1,
                    alpha,
                    beta
                );
            }

            long worst = INF;

            for (int d : choices.get(i)) {
                moves[i] = d;

                worst = Math.min(
                    worst,
                    combinations(
                        pos,
                        choices,
                        moves,
                        i + 1,
                        depth,
                        alpha,
                        beta
                    )
                );

                beta = Math.min(beta, worst);

                if (alpha >= beta) {
                    break;
                }
            }

            return worst;
        }

        int choose() {
            Position pos = initial;
            List<Integer> order = rootOrder(pos);

            if (order.isEmpty()) {
                return emergency(pos, 0);
            }

            int chosen = order.get(0);

            if (order.size() == 1) {
                return chosen;
            }

            for (int depth = 1; depth <= 8; depth++) {
                int best = chosen;
                long value = -INF;
                long alpha = -INF;

                try {
                    for (int d : order) {
                        long score = minimize(
                            pos,
                            d,
                            depth,
                            alpha,
                            INF
                        );

                        if (score > value) {
                            best = d;
                            value = score;
                        }

                        alpha = Math.max(alpha, value);
                    }

                } catch (Deadline exhausted) {
                    break;
                }

                // Resultado parcial nao altera uma escolha completa.
                if (value <= -WIN / 4) {
                    break;
                }

                chosen = best;

                order.remove(Integer.valueOf(chosen));
                order.add(0, chosen);

                if (value >= WIN / 2) {
                    break;
                }
            }

            return chosen;
        }
    }
}