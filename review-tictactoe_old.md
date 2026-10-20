# Tic Tac Toe System - Code Review

## Overall Rating: B (82/100)

### Rating Breakdown
- **SOLID Principles**: A- (88/100)
- **Concurrency**: B+ (85/100)
- **Design Patterns**: B (80/100)
- **Code Quality**: B+ (87/100)
- **Maintainability**: A- (90/100)

---

## Executive Summary

The Tic Tac Toe system demonstrates clean, simple game logic with proper synchronization. The implementation is straightforward and easy to understand. However, it has a **critical mutability leak** in `getBoard()`, lacks input validation, and doesn't support multiplayer sessions. Suitable for single-player or trusted environments, but needs enhancements for production multi-player use.

---

## Strengths

### 1. Clean Game Logic ✅
**Location**: `model/TicTacToeGame.java:26-43`

```java
public synchronized boolean makeMove(int row, int col) {
    if (status != GameStatus.IN_PROGRESS || board[row][col] != '-') {
        return false;
    }

    board[row][col] = currentPlayer;

    if (checkWin()) {
        status = GameStatus.COMPLETED;
        winner = String.valueOf(currentPlayer);
    } else if (isBoardFull()) {
        status = GameStatus.DRAW;
    } else {
        currentPlayer = (currentPlayer == 'X') ? 'O' : 'X';
    }

    return true;
}
```

Clear state machine: check validity → make move → check win/draw → switch player.

### 2. Proper Synchronization ✅
**Location**: `model/TicTacToeGame.java:26`

```java
public synchronized boolean makeMove(int row, int col)
```

Method-level synchronization prevents race conditions in concurrent move attempts.

### 3. Correct Win Detection ✅
**Location**: `model/TicTacToeGame.java:45-55`

```java
private boolean checkWin() {
    // Check rows and columns
    for (int i = 0; i < 3; i++) {
        if (board[i][0] == currentPlayer && board[i][1] == currentPlayer && board[i][2] == currentPlayer) return true;
        if (board[0][i] == currentPlayer && board[1][i] == currentPlayer && board[2][i] == currentPlayer) return true;
    }
    // Check diagonals
    if (board[0][0] == currentPlayer && board[1][1] == currentPlayer && board[2][2] == currentPlayer) return true;
    if (board[0][2] == currentPlayer && board[1][1] == currentPlayer && board[2][0] == currentPlayer) return true;
    return false;
}
```

Correctly checks all 8 winning conditions (3 rows, 3 columns, 2 diagonals).

### 4. Immutable Game ID ✅
**Location**: `model/TicTacToeGame.java:4, 10`

```java
private final String gameId;

public TicTacToeGame(String gameId) {
    this.gameId = gameId;
    // ...
}
```

Game ID cannot be changed after creation.

---

## Critical Issues

### 🔴 ISSUE #1: Mutability Leak - Board Can Be Modified Externally (CRITICAL)

**Location**: `model/TicTacToeGame.java:67`

**Problem**: Returns direct reference to internal board array

```java
public char[][] getBoard() {
    return board;  // ❌ Exposes mutable internal state
}
```

**Impact**: Callers can bypass game logic and modify board directly

**Exploit Scenario**:
```java
TicTacToeGame game = service.createGame();

// ❌ Caller can cheat by modifying board directly
char[][] board = game.getBoard();
board[0][0] = 'X';
board[0][1] = 'X';
board[0][2] = 'X';
// Game logic thinks X just won without making moves!

// ❌ Can also clear the board
for (int i = 0; i < 3; i++) {
    for (int j = 0; j < 3; j++) {
        board[i][j] = '-';
    }
}
// Game reset without going through proper channels
```

**Fix: Return Defensive Copy**
```java
public char[][] getBoard() {
    // ✅ Return deep copy to prevent external modification
    char[][] copy = new char[3][3];
    for (int i = 0; i < 3; i++) {
        System.arraycopy(board[i], 0, copy[i], 0, 3);
    }
    return copy;
}

// OR use modern Java:
public char[][] getBoard() {
    return Arrays.stream(board)
                 .map(char[]::clone)
                 .toArray(char[][]::new);
}

// OR better: return immutable representation
public List<List<Character>> getBoardAsList() {
    return Arrays.stream(board)
                 .map(row -> IntStream.range(0, row.length)
                                     .mapToObj(i -> row[i])
                                     .collect(Collectors.toUnmodifiableList()))
                 .collect(Collectors.toUnmodifiableList());
}

// OR simplest: return String representation
public String getBoardAsString() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 3; i++) {
        sb.append(board[i][0]).append(board[i][1]).append(board[i][2]);
        if (i < 2) sb.append("\n");
    }
    return sb.toString();
}
```

---

### 🔴 ISSUE #2: No Input Validation - ArrayIndexOutOfBoundsException (CRITICAL)

**Location**: `model/TicTacToeGame.java:26-29`

**Problem**: No bounds checking for row and col parameters

```java
public synchronized boolean makeMove(int row, int col) {
    if (status != GameStatus.IN_PROGRESS || board[row][col] != '-') {
        // ❌ If row or col is out of bounds, ArrayIndexOutOfBoundsException!
        return false;
    }
}
```

**Failure Scenario**:
```java
game.makeMove(-1, 0);   // 💥 ArrayIndexOutOfBoundsException
game.makeMove(0, 5);    // 💥 ArrayIndexOutOfBoundsException
game.makeMove(10, 10);  // 💥 ArrayIndexOutOfBoundsException
```

**Fix: Add Bounds Validation**
```java
public synchronized boolean makeMove(int row, int col) {
    // ✅ Validate bounds first
    if (row < 0 || row >= 3 || col < 0 || col >= 3) {
        return false;  // Invalid position
    }

    if (status != GameStatus.IN_PROGRESS) {
        return false;  // Game already finished
    }

    if (board[row][col] != '-') {
        return false;  // Position already occupied
    }

    // Now safe to make move
    board[row][col] = currentPlayer;

    if (checkWin()) {
        status = GameStatus.COMPLETED;
        winner = String.valueOf(currentPlayer);
    } else if (isBoardFull()) {
        status = GameStatus.DRAW;
    } else {
        currentPlayer = (currentPlayer == 'X') ? 'O' : 'X';
    }

    return true;
}
```

---

### 🟡 ISSUE #3: No Player Session Management (MEDIUM Priority)

**Location**: `model/TicTacToeGame.java:26`

**Problem**: Anyone can make a move, no player authentication

```java
public synchronized boolean makeMove(int row, int col) {
    // ❌ No check if correct player is making the move
    board[row][col] = currentPlayer;
}
```

**Impact**: In multiplayer scenarios, players can make moves out of turn

**Scenario**:
```java
// Player X creates game
TicTacToeGame game = service.createGame();

// Player X makes move
game.makeMove(0, 0);  // X's turn

// Player X makes another move (should be O's turn)
game.makeMove(0, 1);  // ❌ Succeeds! X plays twice
```

**Fix: Add Player Session Tracking**
```java
public class TicTacToeGame {
    private final String gameId;
    private final String playerX;  // Player X session ID
    private final String playerO;  // Player O session ID
    private final char[][] board;
    private char currentPlayer;
    private GameStatus status;
    private String winner;

    public TicTacToeGame(String gameId, String playerX) {
        this.gameId = gameId;
        this.playerX = playerX;
        this.playerO = null;  // Waiting for second player
        this.board = new char[3][3];
        this.currentPlayer = 'X';
        this.status = GameStatus.WAITING_FOR_PLAYER;
        initializeBoard();
    }

    public synchronized boolean joinGame(String playerId) {
        if (playerO == null && !playerId.equals(playerX)) {
            this.playerO = playerId;
            this.status = GameStatus.IN_PROGRESS;
            return true;
        }
        return false;
    }

    public synchronized boolean makeMove(String playerId, int row, int col) {
        // ✅ Validate it's the correct player's turn
        if (currentPlayer == 'X' && !playerId.equals(playerX)) {
            return false;  // Not X's turn
        }
        if (currentPlayer == 'O' && !playerId.equals(playerO)) {
            return false;  // Not O's turn
        }

        // Validate bounds
        if (row < 0 || row >= 3 || col < 0 || col >= 3) {
            return false;
        }

        if (status != GameStatus.IN_PROGRESS) {
            return false;
        }

        if (board[row][col] != '-') {
            return false;
        }

        // Make move
        board[row][col] = currentPlayer;

        if (checkWin()) {
            status = GameStatus.COMPLETED;
            winner = playerId;
        } else if (isBoardFull()) {
            status = GameStatus.DRAW;
        } else {
            currentPlayer = (currentPlayer == 'X') ? 'O' : 'X';
        }

        return true;
    }

    public String getPlayerX() { return playerX; }
    public String getPlayerO() { return playerO; }
}
```

---

### 🟡 ISSUE #4: No Game Cleanup - Memory Leak (MEDIUM Priority)

**Location**: `service/TicTacToeService.java:10`

**Problem**: Games never removed from ConcurrentHashMap

```java
private final ConcurrentHashMap<String, TicTacToeGame> games = new ConcurrentHashMap<>();

public TicTacToeGame createGame() {
    String gameId = UUID.randomUUID().toString();
    TicTacToeGame game = new TicTacToeGame(gameId);
    games.put(gameId, game);  // ❌ Never removed
    return game;
}
```

**Impact**: Old finished games remain in memory forever

**Scenario**:
```java
// Create 1 million games over time
for (int i = 0; i < 1_000_000; i++) {
    service.createGame();
}
// ❌ All 1 million games still in memory
// Result: OutOfMemoryError
```

**Fix: Add Game Expiry and Cleanup**
```java
@Service
public class TicTacToeService {
    private final ConcurrentHashMap<String, TicTacToeGame> games = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> gameCreationTimes = new ConcurrentHashMap<>();

    private static final Duration GAME_EXPIRY = Duration.ofHours(1);

    public TicTacToeGame createGame() {
        String gameId = UUID.randomUUID().toString();
        TicTacToeGame game = new TicTacToeGame(gameId);
        games.put(gameId, game);
        gameCreationTimes.put(gameId, Instant.now());
        return game;
    }

    public void removeGame(String gameId) {
        games.remove(gameId);
        gameCreationTimes.remove(gameId);
    }

    // ✅ Scheduled cleanup of expired games
    @Scheduled(fixedRate = 300000)  // Every 5 minutes
    public void cleanupExpiredGames() {
        Instant now = Instant.now();
        List<String> expiredGames = new ArrayList<>();

        gameCreationTimes.forEach((gameId, creationTime) -> {
            if (Duration.between(creationTime, now).compareTo(GAME_EXPIRY) > 0) {
                TicTacToeGame game = games.get(gameId);
                // Remove if expired OR if game is finished
                if (game == null || game.getStatus() != GameStatus.IN_PROGRESS) {
                    expiredGames.add(gameId);
                }
            }
        });

        expiredGames.forEach(gameId -> {
            games.remove(gameId);
            gameCreationTimes.remove(gameId);
        });

        if (!expiredGames.isEmpty()) {
            log.info("Cleaned up {} expired games", expiredGames.size());
        }
    }
}
```

---

### 🟢 ISSUE #5: No Move History (LOW Priority - Feature Gap)

**Location**: System-wide

**Problem**: Cannot replay game or undo moves

**Recommendation**:
```java
public class Move {
    private final int row;
    private final int col;
    private final char player;
    private final Instant timestamp;

    public Move(int row, int col, char player) {
        this.row = row;
        this.col = col;
        this.player = player;
        this.timestamp = Instant.now();
    }

    // Getters...
}

public class TicTacToeGame {
    private final List<Move> moveHistory = new ArrayList<>();

    public synchronized boolean makeMove(int row, int col) {
        // ... validation and move logic

        // ✅ Record move in history
        moveHistory.add(new Move(row, col, currentPlayer));

        // ... continue
    }

    public List<Move> getMoveHistory() {
        return Collections.unmodifiableList(moveHistory);
    }

    public synchronized boolean undoLastMove() {
        if (moveHistory.isEmpty()) {
            return false;
        }

        Move lastMove = moveHistory.remove(moveHistory.size() - 1);
        board[lastMove.getRow()][lastMove.getCol()] = '-';
        currentPlayer = lastMove.getPlayer();  // Switch back
        status = GameStatus.IN_PROGRESS;
        winner = null;
        return true;
    }
}
```

---

## Additional Recommendations

### 1. Add AI Opponent
```java
public class AIPlayer {
    public static Move getBestMove(TicTacToeGame game, char aiPlayer) {
        // Minimax algorithm
        int bestScore = Integer.MIN_VALUE;
        Move bestMove = null;

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (game.getBoard()[i][j] == '-') {
                    // Try move
                    game.makeMove(i, j);
                    int score = minimax(game, 0, false, aiPlayer);
                    game.undoLastMove();

                    if (score > bestScore) {
                        bestScore = score;
                        bestMove = new Move(i, j, aiPlayer);
                    }
                }
            }
        }

        return bestMove;
    }

    private static int minimax(TicTacToeGame game, int depth, boolean isMaximizing, char aiPlayer) {
        // Implement minimax with alpha-beta pruning
        // ...
    }
}
```

### 2. Add Game Events
```java
public interface GameEventListener {
    void onGameStarted(String gameId);
    void onMoveMade(String gameId, int row, int col, char player);
    void onGameFinished(String gameId, GameStatus status, String winner);
}

public class TicTacToeGame {
    private final List<GameEventListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(GameEventListener listener) {
        listeners.add(listener);
    }

    public synchronized boolean makeMove(int row, int col) {
        // ... move logic

        // ✅ Notify listeners
        listeners.forEach(listener ->
            listener.onMoveMade(gameId, row, col, currentPlayer));

        if (status == GameStatus.COMPLETED || status == GameStatus.DRAW) {
            listeners.forEach(listener ->
                listener.onGameFinished(gameId, status, winner));
        }

        return true;
    }
}
```

### 3. Add Difficulty Levels
```java
public enum Difficulty {
    EASY,    // Random moves
    MEDIUM,  // Mix of random and optimal
    HARD     // Always optimal (minimax)
}

public class AIPlayer {
    public static Move getMove(TicTacToeGame game, char aiPlayer, Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> getRandomMove(game);
            case MEDIUM -> Math.random() > 0.5 ? getBestMove(game, aiPlayer) : getRandomMove(game);
            case HARD -> getBestMove(game, aiPlayer);
        };
    }
}
```

---

## Testing Recommendations

### Test #1: Detect Mutability Leak
```java
@Test
void testBoardImmutability() {
    TicTacToeGame game = new TicTacToeGame("game1");

    // Get board and try to modify it
    char[][] board = game.getBoard();
    board[0][0] = 'X';
    board[1][1] = 'O';

    // ❌ Current implementation: game state is corrupted
    // ✅ After fix: game state is unchanged
    assertEquals('-', game.getBoard()[0][0],
                "External modification should not affect game");
}
```

### Test #2: Boundary Validation
```java
@Test
void testInvalidMoves() {
    TicTacToeGame game = new TicTacToeGame("game1");

    // Test boundary conditions
    assertFalse(game.makeMove(-1, 0), "Negative row should fail");
    assertFalse(game.makeMove(0, -1), "Negative col should fail");
    assertFalse(game.makeMove(3, 0), "Row >= 3 should fail");
    assertFalse(game.makeMove(0, 3), "Col >= 3 should fail");
    assertFalse(game.makeMove(100, 100), "Large indices should fail");
}
```

### Test #3: Win Detection
```java
@Test
void testWinConditions() {
    TicTacToeGame game = new TicTacToeGame("game1");

    // Test horizontal win
    game.makeMove(0, 0);  // X
    game.makeMove(1, 0);  // O
    game.makeMove(0, 1);  // X
    game.makeMove(1, 1);  // O
    game.makeMove(0, 2);  // X wins

    assertEquals(GameStatus.COMPLETED, game.getStatus());
    assertEquals("X", game.getWinner());
}

@Test
void testDrawCondition() {
    TicTacToeGame game = new TicTacToeGame("game1");

    // Play to draw
    game.makeMove(0, 0);  // X
    game.makeMove(0, 1);  // O
    game.makeMove(0, 2);  // X
    game.makeMove(1, 0);  // O
    game.makeMove(1, 1);  // X
    game.makeMove(2, 0);  // O
    game.makeMove(1, 2);  // X
    game.makeMove(2, 2);  // O
    game.makeMove(2, 1);  // X - Draw

    assertEquals(GameStatus.DRAW, game.getStatus());
    assertNull(game.getWinner());
}
```

---

## Performance Analysis

### Current Performance
- **Create Game**: O(1) - HashMap put
- **Make Move**: O(1) - Synchronized method with fixed 3x3 board
- **Check Win**: O(1) - Fixed 8 conditions
- **Get Board**: O(1) - Direct reference (❌ should be O(n²) for copy)

### Memory Usage
- **Per game**: ~200 bytes (game object + 3x3 char array)
- **10K active games**: ~2 MB
- **Risk**: Without cleanup, memory grows unbounded

---

## Conclusion

The Tic Tac Toe system is a **clean, simple implementation** suitable for learning and basic use cases. The main issues are the mutability leak and lack of input validation, which must be fixed before production use.

### Severity Assessment
🔴 **CRITICAL**: Mutability leak in getBoard()
🔴 **CRITICAL**: No input validation (ArrayIndexOutOfBoundsException)
🟡 **MEDIUM**: No player session management
🟡 **MEDIUM**: No game cleanup (memory leak)
🟢 **LOW**: Missing features (move history, AI, events)

### Action Items
1. ✅ **IMMEDIATELY** fix getBoard() to return defensive copy
2. ✅ **IMMEDIATELY** add bounds validation for row/col
3. ⚠️ **Add player session management** for multiplayer
4. ⚠️ **Add game cleanup** with scheduled task
5. ⚠️ **Add move history** for replay/undo functionality
6. ⚠️ **Add AI opponent** for single-player mode

**Verdict**: CONDITIONALLY APPROVED. Fix critical mutability and validation issues before deployment.

### Recommended Timeline
**Week 1**: Fix mutability leak, add bounds validation
**Week 2**: Add player session management, comprehensive tests
**Week 3**: Add game cleanup, move history
**Week 4**: Add AI opponent, difficulty levels, events

**Can deploy after Week 1 fixes for basic multiplayer use.**
