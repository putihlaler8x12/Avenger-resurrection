package resurrection.strikeforce;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Off-chain command hub for Avenger Resurrection strike-force missions.
 * Tracks phase gates, squad slots and reward splits. Retro action-commander style.
 */
public final class AvengerResurrection {

    public static final int MAX_SQUAD_SIZE = 12;
    public static final long COOLDOWN_BLOCKS = 47L;
    public static final int MISSION_CAP_PER_PHASE = 99;
    public static final long REWARD_BASE_UNITS = 1000L;
    public static final long PHASE_DURATION_BLOCKS = 312L;
    public static final int MAX_PHASE_INDEX = 5;
    public static final int TICK_BASE = 17;
    public static final int VAULT_SHARE_BPS = 85;
    public static final int CONTROL_SHARE_BPS = 15;

    private final String commanderTower;
    private final String missionControl;
    private final String vaultHub;

    private final AtomicLong missionCounter = new AtomicLong(0L);
    private final Map<Long, MissionRecord> missions = new ConcurrentHashMap<>();
    private final Map<String, Integer> agentToSquadSlot = new ConcurrentHashMap<>();
    private final Map<Integer, SquadMember> squadSlotToMember = new ConcurrentHashMap<>();
    private final Map<Long, Long> missionCooldownUntil = new ConcurrentHashMap<>();
    private volatile long totalRewardsDisbursed;
    private volatile boolean paused;

    public AvengerResurrection() {
        this.commanderTower = "0x8F2a4C6e1B0d3A5f7E9c2b4D6a8F0e1C3B5d7E9";
        this.missionControl = "0x1E7b9D3f5A0c2E4d6F8a0B2c4D6e8F0a2B4c6D8";
        this.vaultHub = "0x5C9e2A4b6D8f0c1E3a5B7d9F1c3E5a7B9d1F3e5";
        this.paused = false;
    }

    public AvengerResurrection(String commanderTower, String missionControl, String vaultHub) {
        if (commanderTower == null || missionControl == null || vaultHub == null) {
            throw new IllegalArgumentException("ZeroAddressDisallowed");
        }
        this.commanderTower = commanderTower;
        this.missionControl = missionControl;
        this.vaultHub = vaultHub;
        this.paused = false;
    }

    public static final class MissionRecord {
        private final long startBlock;
        private volatile int phase;
        private volatile boolean terminated;
        private volatile long rewardClaimed;

        public MissionRecord(long startBlock, int phase, boolean terminated, long rewardClaimed) {
            this.startBlock = startBlock;
            this.phase = phase;
            this.terminated = terminated;
            this.rewardClaimed = rewardClaimed;
        }

        public long getStartBlock() { return startBlock; }
        public int getPhase() { return phase; }
        public void setPhase(int phase) { this.phase = phase; }
        public boolean isTerminated() { return terminated; }
        public void setTerminated(boolean terminated) { this.terminated = terminated; }
        public long getRewardClaimed() { return rewardClaimed; }
        public void setRewardClaimed(long rewardClaimed) { this.rewardClaimed = rewardClaimed; }
    }

    public static final class SquadMember {
        private final String agent;
        private final long enlistedAtBlock;
        private volatile boolean active;

        public SquadMember(String agent, long enlistedAtBlock, boolean active) {
            this.agent = Objects.requireNonNull(agent);
            this.enlistedAtBlock = enlistedAtBlock;
            this.active = active;
        }

        public String getAgent() { return agent; }
        public long getEnlistedAtBlock() { return enlistedAtBlock; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    public enum PhaseGate {
        PHASE_1(1), PHASE_2(2), PHASE_3(3), PHASE_4(4), PHASE_5(5);
        private final int index;
        PhaseGate(int index) { this.index = index; }
        public int getIndex() { return index; }
    }

    public long launchMission(long currentBlock) {
        if (paused) throw new IllegalStateException("PausedByCommander");
        long missionId = missionCounter.incrementAndGet();
        missions.put(missionId, new MissionRecord(currentBlock, 1, false, 0L));
        missionCooldownUntil.put(missionId, currentBlock + COOLDOWN_BLOCKS);
        return missionId;
    }

    public void advancePhase(long missionId, long currentBlock) {
        if (paused) throw new IllegalStateException("PausedByCommander");
        MissionRecord m = missions.get(missionId);
        if (m == null) throw new IllegalArgumentException("MissionDoesNotExist");
        if (m.isTerminated()) throw new IllegalStateException("MissionAlreadyTerminated");
        if (m.getStartBlock() == 0) throw new IllegalStateException("InvalidPhaseTransition");
        int nextPhase = m.getPhase() + 1;
        if (nextPhase > MAX_PHASE_INDEX) throw new IllegalStateException("InvalidPhaseTransition");
        m.setPhase(nextPhase);
    }

    public void terminateMission(long missionId) {
        MissionRecord m = missions.get(missionId);
        if (m == null) throw new IllegalArgumentException("MissionDoesNotExist");
        if (m.isTerminated()) throw new IllegalStateException("MissionAlreadyTerminated");
        m.setTerminated(true);
    }

    public void assignSquadSlot(String agent, int slot, long currentBlock) {
        if (paused) throw new IllegalStateException("PausedByCommander");
        if (agent == null || agent.isEmpty()) throw new IllegalArgumentException("ZeroAddressDisallowed");
        if (slot <= 0 || slot > MAX_SQUAD_SIZE) throw new IllegalArgumentException("SquadOverCapacity");
        SquadMember existing = squadSlotToMember.get(slot);
        if (existing != null && existing.isActive()) throw new IllegalStateException("SlotAlreadyFilled");
        agentToSquadSlot.put(agent, slot);
        squadSlotToMember.put(slot, new SquadMember(agent, currentBlock, true));
    }

    public void revokeSquadSlot(int slot) {
        SquadMember sm = squadSlotToMember.get(slot);
        if (sm == null) throw new IllegalStateException("AgentNotEnlisted");
        agentToSquadSlot.remove(sm.getAgent());
        squadSlotToMember.remove(slot);
    }

    public void claimMissionReward(long missionId, String recipient, long currentBlock) {
        if (paused) throw new IllegalStateException("PausedByCommander");
        MissionRecord m = missions.get(missionId);
        if (m == null) throw new IllegalArgumentException("MissionDoesNotExist");
        if (m.isTerminated()) throw new IllegalStateException("MissionAlreadyTerminated");
        if (m.getRewardClaimed() > 0) throw new IllegalStateException("RewardPoolExhausted");
        if (currentBlock < m.getStartBlock() + PHASE_DURATION_BLOCKS) throw new IllegalStateException("PhaseLocked");
        long amount = REWARD_BASE_UNITS * MISSION_CAP_PER_PHASE;
        m.setRewardClaimed(amount);
        totalRewardsDisbursed += amount;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public String getCommanderTower() { return commanderTower; }
    public String getMissionControl() { return missionControl; }
    public String getVaultHub() { return vaultHub; }
    public long getMissionCount() { return missionCounter.get(); }
    public long getTotalRewardsDisbursed() { return totalRewardsDisbursed; }
    public boolean isPaused() { return paused; }
