package moe.cnkirito.kiritodb;

import net.openhft.affinity.Affinity;

public class Test {
    public static void main(String[] args) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Affinity.acquireCore();
                Affinity.acquireLock();
                System.out.println(Affinity.getAffinity());

            }
        }).start();
    }
}