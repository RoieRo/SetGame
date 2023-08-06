package bguspl.set.ex;

import java.util.LinkedList;
import java.util.logging.Level;

import bguspl.set.Env;
import java.util.stream.Collectors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.*;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate
     * key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * When a player declares to the Dealer that he holds a set, The dealer will let
     * the player know if his set is correct or not in this data structure.
     */
    public ArrayBlockingQueue<Integer> legalSet;

    /**
     * Queue of incoming actions using Blocking Queue using take and add in size 3.
     */
    private ArrayBlockingQueue<Integer> inputQueue;

    /**
     * a flag to inputManger Thread that let him know if he can continue
     * will turn off in cases of penalty or point
     */
    public volatile boolean canInsertAction;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided
     *               manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.inputQueue = new ArrayBlockingQueue<Integer>(3, true);
        this.legalSet = new ArrayBlockingQueue<Integer>(1, true);
        this.canInsertAction = true;
    }

    /**
     * The main player thread of each player starts here (main loop for the player
     * thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
        if (!human)
            createArtificialIntelligence();

        while (!terminate) {
            if (table.playerCanAccess && canInsertAction) {
                int slot = 0;
                try {//waiting until there is a keyPress in the input queue
                    slot = inputQueue.take();
                } catch (InterruptedException ignored) {}
               
                if (!table.removeToken(id, slot)) { // we need to lay the token on the table
                    //Checks if this player can put another token on the table
                    if (table.playerSlots[id].size() < 3) { 
                        table.placeToken(id, slot);
                        if (table.playerSlots[id].size() == 3) {// checks this player found a set
                            // Dealer should check set
                            Integer answer = null;
                            try {
                                table.playersWithSets.put(id);
                                answer = legalSet.take(); //reciving Dealer's answer
                            } catch (InterruptedException ignored) {}

                            //If the set is legal - Dealer will put 1 
                            //If the set is  not legal - Dealer will put 0
                            //If one of the tokens was removed - Dealer will return null
                            if (answer != null && answer != -1 && !terminate) {
                                if (answer == 1) {
                                    answer = null;
                                    point();
                                } else {
                                    answer = null;
                                    penalty();
                                }
                            }
                        }
                    } else { //we have less then 3 tokens on the table
                        continue;
                    }
                }
            } else { //The player's keyPress are irrelevent
                inputQueue.clear();
            }
        }

        if (!human) {
            try{
                aiThread.join();
            } catch (InterruptedException ignored) {}
            
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of
     * this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it
     * is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                if (table.playerCanAccess && canInsertAction) {
                    Random random = new Random();
                    int keyPress = random.nextInt(env.config.tableSize);
                    keyPressed(keyPress);
                }
            }
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     * changing the flag to true
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * returns terminate status
     */
    public boolean getTerminate() {
        return terminate;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {

        if (table.playerCanAccess && canInsertAction) {
            if (inputQueue.size() < 3) {
                inputQueue.add(slot);
            }
        }

    }

    /**
     * Award a point to a player and perform other related actions.
     * freese the player for a second
     * 
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        long timeOfPointFreeze = env.config.pointFreezeMillis;
        env.ui.setFreeze(id, timeOfPointFreeze);
        while (timeOfPointFreeze > 1000) {
            try {
                Thread.currentThread().sleep(1000);
            } catch (InterruptedException e) {}
            timeOfPointFreeze = timeOfPointFreeze - 1000;
            env.ui.setFreeze(id, timeOfPointFreeze);
        }
        if (timeOfPointFreeze > 0) {
            try {
                Thread.currentThread().sleep(timeOfPointFreeze);
            } catch (InterruptedException e) {
            }

        }
        env.ui.setFreeze(id, 0);

        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        score = score + 1;
        env.ui.setScore(id, score);
        canInsertAction = true;
    }

    /**
     * Penalize a player and perform other related actions.
     * 
     */
    public void penalty() {
        long timeOfPenalty = env.config.penaltyFreezeMillis;
        env.ui.setFreeze(id, timeOfPenalty);
        while (timeOfPenalty > 1000) {
            try {
                Thread.currentThread().sleep(1000);
            } catch (Exception e) {
            }
            timeOfPenalty = timeOfPenalty - 1000;
            env.ui.setFreeze(id, timeOfPenalty);
        }
        if (timeOfPenalty > 0) {
            try {
                Thread.currentThread().sleep(timeOfPenalty);
            } catch (Exception e) {
            }

        }

        env.ui.setFreeze(id, 0);
        canInsertAction = true;
    }

    /**
     * returns this player's score
     */
    public int getScore() {
        return score;
    }

    /**
     * Sets this player's score
     */
    public void setScore(int score) {
        this.score = score;
    }
}
