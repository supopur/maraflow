package cz.maramat.undo;

import cz.maramat.model.Page;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class UndoManager50 {
    public static final int MAX_HISTORY = 50;

    public record HistoryEntry(String actionName, int pageIndex, Page pageSnapshotBefore, Page pageSnapshotAfter) {}

    private final Deque<HistoryEntry> undoStack = new ArrayDeque<>();
    private final Deque<HistoryEntry> redoStack = new ArrayDeque<>();
    private final List<Runnable> changeListeners = new ArrayList<>();

    public void recordPageAction(String actionName, int pageIndex, Page beforeState, Page afterState) {
        if (undoStack.size() >= MAX_HISTORY) {
            undoStack.removeLast();
        }
        undoStack.addFirst(new HistoryEntry(actionName, pageIndex, beforeState.copy(), afterState.copy()));
        redoStack.clear();
        notifyListeners();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public List<HistoryEntry> getUndoEntries() {
        return new ArrayList<>(undoStack);
    }

    public List<HistoryEntry> getRedoEntries() {
        return new ArrayList<>(redoStack);
    }

    /**
     * Undoes the top 'count' operations (1..count) and returns the last undone entry so the caller can restore it.
     */
    public HistoryEntry undoSteps(int count, List<Page> projectPages) {
        int steps = Math.min(count, undoStack.size());
        HistoryEntry last = null;
        for (int i = 0; i < steps; i++) {
            HistoryEntry entry = undoStack.removeFirst();
            if (entry.pageIndex() >= 0 && entry.pageIndex() < projectPages.size()) {
                projectPages.set(entry.pageIndex(), entry.pageSnapshotBefore().copy());
            }
            if (redoStack.size() >= MAX_HISTORY) {
                redoStack.removeLast();
            }
            redoStack.addFirst(entry);
            last = entry;
        }
        notifyListeners();
        return last;
    }

    /**
     * Redoes the top 'count' operations (1..count) and returns the last redone entry.
     */
    public HistoryEntry redoSteps(int count, List<Page> projectPages) {
        int steps = Math.min(count, redoStack.size());
        HistoryEntry last = null;
        for (int i = 0; i < steps; i++) {
            HistoryEntry entry = redoStack.removeFirst();
            if (entry.pageIndex() >= 0 && entry.pageIndex() < projectPages.size()) {
                projectPages.set(entry.pageIndex(), entry.pageSnapshotAfter().copy());
            }
            if (undoStack.size() >= MAX_HISTORY) {
                undoStack.removeLast();
            }
            undoStack.addFirst(entry);
            last = entry;
        }
        notifyListeners();
        return last;
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
        notifyListeners();
    }

    public void addChangeListener(Runnable r) {
        changeListeners.add(r);
    }

    private void notifyListeners() {
        for (Runnable r : changeListeners) {
            r.run();
        }
    }
}
