package dk.ilios.hivemind.ai;

import dk.ilios.hivemind.ai.heuristics.BoardValueHeuristic;
import dk.ilios.hivemind.ai.transpositiontable.TranspositionTable;
import dk.ilios.hivemind.ai.transpositiontable.TranspositionTableEntry;
import dk.ilios.hivemind.game.Game;
import dk.ilios.hivemind.game.GameCommand;
import dk.ilios.hivemind.model.Board;
import dk.ilios.hivemind.utils.LimitedBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * AI that implements Minimax tree search algorithm with Alpha-Beta prunning and Iterative Deepening Depth-First Search.
 * Backed by a transposition table. Killer Heuristic applied to each ply as well.
 */
public class KillerHeuristicTranspostionTableIDDFSAlphaBetaMiniMaxAI extends AbstractMinMaxAI {

    private Random random = new Random();
    private TranspositionTable table = new TranspositionTable();
    private ArrayList<LimitedBuffer<GameCommand>> killerMoves = new ArrayList<LimitedBuffer<GameCommand>>();

    public KillerHeuristicTranspostionTableIDDFSAlphaBetaMiniMaxAI(String name, BoardValueHeuristic heuristicFunction, int depth, int maxTimeInMillis) {
        super(name, heuristicFunction, depth, maxTimeInMillis);
        for (int i = 0; i < depth; i++) {
            killerMoves.add(new LimitedBuffer<GameCommand>(2));
        }
    }

    @Override
    public HiveAI copy() {
        return new KillerHeuristicTranspostionTableIDDFSAlphaBetaMiniMaxAI(name, heuristic, searchDepth, maxTimeInMillis);
    }

    @Override
    public GameCommand nextMove(Game state, Board board) {
        start = System.currentTimeMillis();
        maximizingPlayer = state.getActivePlayer();

        // Clear previous killer moves
        for (LimitedBuffer<GameCommand> buffer : killerMoves) {
            buffer.clear();
        }

        // Iterate depths, effectively a breath-first search, where top nodes get visited multiple times
        int depth = 0;
        int bestValue = Integer.MIN_VALUE;
        GameCommand bestCommand = null;

        Object[] result = new Object[2];
        while(depth <= searchDepth && System.currentTimeMillis() - start < maxTimeInMillis) {
            result = runMinMax(state, depth, result);
            int val = (Integer) result[0];
            if (val > bestValue || val == bestValue && random.nextBoolean()) {
                bestValue = val;
                bestCommand = (GameCommand) result[1];
                if (bestValue == HiveAI.MAX) {
                    return bestCommand; // Game winning move
                }
            }

            depth++;
        }

        return bestCommand; // 2nd best move
    }

    private Object[] runMinMax(Game state, int searchDepth, Object[] result) {

        // Minimax traversal of game tree
        List<GameCommand> moves = generateMoves(state);
        int bestValue = Integer.MIN_VALUE;
        GameCommand bestMove = GameCommand.PASS;

        for (GameCommand move : moves) {
            // Update game state and continue traversel
            applyMove(move, state);
            int value = alphabeta(state, searchDepth - 1, bestValue, Integer.MAX_VALUE, false);
            if (value > bestValue || value == bestValue && random.nextBoolean()) {
                bestValue = value;
                bestMove = move;
            }
            undoMove(move, state);
        }

        result[0] = bestValue;
        result[1] = bestMove;
        return result;
    }

    private int alphabeta(Game state, int depth, int alpha, int beta, boolean maximizingPlayer) {

        int originalAlpha = alpha;
        int originalBeta = beta;
        GameCommand bestMove = null;

        // Check transposition table and adjust values if needed or return result if possible
        long zobristKey = state.getZobristKey();
        TranspositionTableEntry entry = table.getResult(zobristKey);
        if (entry != null && entry.depth >= depth) {
            aiStats.cacheHit();
            bestMove = entry.move;
            if (entry.type == TranspositionTableEntry.PV_NODE) {
                return entry.value;
            } else if (entry.type == TranspositionTableEntry.CUT_NODE && entry.value > alpha) {
                alpha = entry.value;
            } else if (entry.type == TranspositionTableEntry.ALL_NODE && entry.value < beta) {
                beta = entry.value;
            }

            if (alpha >= beta) {
                return entry.value; // Lowerbound is better than upper bound
            }
        }


        // Run algorithm as usual
        int value;
        if (isGameOver(state, depth) || depth <= 0 || System.currentTimeMillis() - start > maxTimeInMillis) {
            value = value(state);
        } else {

            // Generate moves
            GameCommand[] killMoves = new GameCommand[2];
            killerMoves.get(depth).toArray(killMoves);
            List<GameCommand> moves = generateMoves(state, bestMove, killMoves[0], killMoves[1]);
            int moveEvaluated = 0;

            if (maximizingPlayer) {
                for (GameCommand move : moves) {
                    moveEvaluated++;
                    bestMove = move;
                    applyMove(move, state);
                    value = alphabeta(state, depth - 1, alpha, beta, !maximizingPlayer);
                    if (value > alpha) {
                        alpha = value;
                    }
                    undoMove(move, state);

                    // Beta cut-off
                    if (beta <= alpha) {
                        aiStats.cutOffAfter(moveEvaluated);
                        killerMoves.get(depth).add(move);
                        break;
                    }
                }

                value = alpha;

            } else {

                for (GameCommand move : moves) {
                    moveEvaluated++;
                    bestMove = move;
                    applyMove(move, state);
                    value = alphabeta(state, depth - 1, alpha, beta, !maximizingPlayer);
                    if (value < beta) {
                        beta = value;
                    }
                    undoMove(move, state);

                    // Alpha cut-off
                    if (beta <= alpha) {
                        aiStats.cutOffAfter(moveEvaluated);
                        killerMoves.get(depth).add(move);
                        break;
                    }
                }

                value = beta;
            }
        }

        // Update transposition table
        if (value <= originalAlpha) {
            table.addResult(zobristKey, value, depth, TranspositionTableEntry.CUT_NODE, bestMove);
        } else if (value >= originalBeta) {
            table.addResult(zobristKey, value, depth, TranspositionTableEntry.ALL_NODE, bestMove);
        } else {
            table.addResult(zobristKey, value, depth, TranspositionTableEntry.PV_NODE, bestMove);
        }

        return value;
    }

    @Override
    public boolean maintainsStandardPosition() {
        return true;
    }

}
