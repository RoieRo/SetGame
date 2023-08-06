package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;
    private Thread[] playerThreads;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    private long timeInMilli;

    /**
     * The class constructor.
     *
     * @param env     - the environment object.
     * @param table   - the table object.
     * @param players - array of players objects.
     */
    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.playerThreads = new Thread[players.length]; 

    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        // Creating a thread for each player
        for (int playerId = 0; playerId < players.length; playerId++) {
            playerThreads[playerId] = new Thread(players[playerId], String.valueOf(playerId));
        }
        // Starting each player threads
        for (int playerId = 0; playerId < players.length; playerId++) {
            playerThreads[playerId].start();
        }
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
        while (!shouldFinish()) {
            placeCardsOnTable();
            updateTimerDisplay(true);
            timerLoop();

            interruptFreezedPlayers();
            removeAllCardsFromTable();
            while(!table.playersWithSets.isEmpty()){
                Integer playerid = table.playersWithSets.remove();
                try{
                  players[playerid].legalSet.put(-1);  
                }
                catch(InterruptedException e) {}
            }
            
        }
        removeAllCardsFromTable();
        announceWinners();
        terminate();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");

    }

    private void timerLoop() {
        timeInMilli = env.config.turnTimeoutMillis;
        reshuffleTime = System.currentTimeMillis() + timeInMilli; // updating the next round stopper
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            table.playerCanAccess = true;
            sleepUntilWokenOrTimeout();
            if (table.playerCanAccess) {
                updateTimerDisplay(false);
            }
            reshuffleTime = System.currentTimeMillis() + timeInMilli;
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     * such as click on the exit symbol.
     */
    public void terminate() {

        for (int playerId = players.length - 1; playerId >= 0; playerId--) {
            players[playerId].terminate();
            try {
                playerThreads[playerId].interrupt();
                playerThreads[playerId].join();
            } catch (InterruptedException e) {
                
            }
        }
        terminate = true;
    }

    /**
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        // Creates a list of slots' indexes starting from 0 to table.size-1
        List<Integer> shuffeledSlots = IntStream.rangeClosed(0, env.config.tableSize - 1).boxed()
                .collect(Collectors.toList());
        Collections.shuffle(shuffeledSlots); // shuffels the slots indexes in random order
        int i = 0;
        while (deck.size() > 0 && i < shuffeledSlots.size()) {

            int slot = shuffeledSlots.get(i);
            if (table.slotToCard[slot] == null) {
                Random random = new Random();
                int indexOfCard = random.nextInt(deck.size());
                int newCard = deck.remove(indexOfCard);
                table.placeCard(newCard, slot);
            }
            i++;
        }
        if (env.config.hints) {
            table.hints();
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some
     * purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        Integer playerid = null;

        long timeToSleep;
        if (timeInMilli > env.config.turnTimeoutWarningMillis) {
            timeToSleep = System.currentTimeMillis() + 900;
        } else {
            timeToSleep = System.currentTimeMillis() + 9;
        }

        while (System.currentTimeMillis() < timeToSleep) {
            try {
                playerid = table.playersWithSets.poll(timeToSleep - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
            }

            if (playerid != null) {
                List<Integer> listtoArray = table.playerSlots[playerid];
                if (listtoArray.size() == 3) {
                    players[playerid].canInsertAction = false;
                    int[] suspectedSet = new int[listtoArray.size()];
                    for (int i = 0; i < listtoArray.size(); i++) {
                        suspectedSet[i] = table.slotToCard[listtoArray.get(i)];

                    }
                    Boolean legalSetFlag = env.util.testSet(suspectedSet);

                    if (legalSetFlag) {
                        try {
                            players[playerid].legalSet.put(1);
                            
                        } catch (InterruptedException ignored) {
                        }
                        table.playerCanAccess = false;
                        legalSetTokenRemoval(suspectedSet);
                        placeCardsOnTable();
                        updateTimerDisplay(true);
                    } else {
                        try {
                            players[playerid].legalSet.put(0);
                        } catch (InterruptedException ignored) {
                        }
                        table.playerCanAccess = true;
                    }

                } else {
                    try {
                        players[playerid].legalSet.put(-1);
                    } catch (InterruptedException ignored) {}
                   
                }
                table.playerCanAccess = true;
            }

        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (reset) {
            timeInMilli = env.config.turnTimeoutMillis;
            env.ui.setCountdown(timeInMilli, false);
        } else {
            if (timeInMilli == 0) {
                return;
            }
            if (timeInMilli <= env.config.turnTimeoutWarningMillis) {
                timeInMilli = timeInMilli - 10;
                env.ui.setCountdown(timeInMilli, true);
            } else {
                timeInMilli = timeInMilli - 1000;
                env.ui.setCountdown(timeInMilli, false);
            }

        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
     void removeAllCardsFromTable() {
        // Creates a list of slots' indexes starting from 0 to table.size-1
        List<Integer> slotsToShuffel = IntStream.rangeClosed(0, env.config.tableSize - 1).boxed()
                .collect(Collectors.toList());
        Collections.shuffle(slotsToShuffel); // shuffels the slots indexes in random order
        // clears the table
        for (int i = 0; i < slotsToShuffel.size(); i++) {
            if (table.slotToCard[slotsToShuffel.get(i)] != null) {
                deck.add(table.slotToCard[slotsToShuffel.get(i)]);
                table.removeCard(slotsToShuffel.get(i));
            }
        }
        table.cleanPlayersSlots();

    }

    /**
     * Check who is/are the winner/s and displays them.
     */
     void announceWinners() {
        LinkedList<Integer> winnersList = new LinkedList<Integer>();
        int winnerScore = -1;
        for (int i = 0; i < players.length; i++) {
            if (players[i].getScore() > winnerScore) {
                winnerScore = players[i].getScore();
            }
        }
        for (int id = 0; id < players.length; id++) {
            if (players[id].getScore() == winnerScore) {
                winnersList.add(id);
            }
        }
        int[] winners = new int[winnersList.size()];
        for (int i = 0; i < winnersList.size(); i++) {
            winners[i] = winnersList.get(i);
        }
        env.ui.announceWinner(winners);
    }

    /**
     * @param set - an array of a legal set cards.
     *            removes only tokens of current set from the table
     */
    private void legalSetTokenRemoval(int[] set) {

        for (int i = 0; i < set.length; i++) {
            int card = set[i];
            int slot = table.cardToSlot[card];
            // removes the card we need to remove from the table from playerSlots
            for (int j = 0; j < table.playerSlots.length; j++) {
                if (table.playerSlots[j].contains(slot)) {
                    table.removeToken(j, slot);
                }
            }
            table.removeCard(slot);
        }
    }

    /**
     * waking up all sleeping/waiting threads when the time interval is over
     */
    private void interruptFreezedPlayers() {
        for (int i = playerThreads.length - 1; i >= 0; i--) {
            if (playerThreads[i].getState() == Thread.State.TIMED_WAITING) {
                playerThreads[i].interrupt();
            }
        }

    }
}