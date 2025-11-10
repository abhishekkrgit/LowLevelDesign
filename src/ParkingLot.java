/**
 * Parking Lot System - Low Level Design (LLD)
 *
 * PROBLEM STATEMENT:
 * ------------------
 * Design a parking lot system that can:
 *   - Handle multiple floors with different slot types (Car, Bike, Truck)
 *   - Allow vehicles to park/unpark
 *   - Generate parking tickets and receipts with pricing
 *   - Handle concurrency (multiple vehicles parking/unparking at once)
 *
 * SOLUTION OVERVIEW:
 * ------------------
 * - Core entities: ParkingLot, ParkingFloor, Slot, Vehicle, ParkingTicket, ParkingReceipt
 * - Strategy Pattern: Used for slot allocation (e.g., NearestParkingStrategy)
 * - Singleton Pattern: ParkingLot as a single instance per system
 * - Thread-safety:
 *      • Each Slot has a ReentrantLock for fine-grained locking.
 *      • ParkingLot has a ReentrantLock (lotLock) for atomic park/unpark operations.
 * - Extensible Design:
 *      • New strategies (pricing, slot selection) can be plugged easily.
 *      • Multiple floors supported.
 *
 * CONCURRENCY PROBLEM ADDRESSED:
 * -------------------------------
 * Without locking, two threads could allocate the same slot simultaneously.
 * ReentrantLock ensures mutual exclusion while assigning or freeing slots.
 *
 * FUTURE IMPROVEMENTS:
 * --------------------
 * - Add PricingStrategy interface for dynamic pricing.
 * - Add ReservationService for pre-booking slots.
 * - Replace coarse-grained lotLock with finer slot-level retry logic for scalability.
 */

// Doc: https://docs.google.com/document/d/1MT0RFvCNRr-9q9xwSVljJpSx0jInKpdGxS3tpKa_NNk/edit?tab=t.0

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Parking Lot System - Full implementation with bounded retry allocation
 *
 * Key points:
 *  - Strategy pattern used for slot selection.
 *  - Per-slot ReentrantLock for fine-grained concurrency control.
 *  - Bounded retries (MAX_RETRIES) used when candidate allocation loses a race.
 *  - CopyOnWriteArrayList used for slot lists (safe iteration + low write frequency).
 *  - Pricing computed by duration (hours rounded up).
 */

/* -------------------- Enums -------------------- */
enum SlotType { CAR, BIKE, TRUCK }
enum VehicleType { CAR, BIKE, TRUCK }
enum SlotState { FREE, OCCUPIED, RESERVED }
enum TicketStatus { OPEN, CLOSED }

/* -------------------- Domain / Entities -------------------- */
class Vehicle {
    private final String plateNo;
    private final VehicleType vehicleType;
    private final String owner;

    public Vehicle(String plateNo, VehicleType vehicleType, String owner) {
        this.plateNo = plateNo;
        this.vehicleType = vehicleType;
        this.owner = owner;
    }

    public String getPlateNo() { return plateNo; }
    public VehicleType getVehicleType() { return vehicleType; }
    public String getOwner() { return owner; }
}

/**
 * ParkingSlot: contains a per-slot lock to make allocate/free atomic.
 */
class ParkingSlot {
    private final String id;
    private final SlotType slotType;
    private SlotState slotState;
    private Vehicle vehicle; // current occupant
    private final Lock mtx = new ReentrantLock();

    public ParkingSlot(String id, SlotType slotType) {
        this.id = id;
        this.slotType = slotType;
        this.slotState = SlotState.FREE;
        this.vehicle = null;
    }

    public String getId() { return id; }
    public SlotType getSlotType() { return slotType; }

    /**
     * Try to allocate this slot to the vehicle. Atomic via mtx.
     * Returns true if allocation succeeded; false if slot not free.
     */
    public boolean allocateVehicle(Vehicle vehicle) {
        if (vehicle == null) return false;
        mtx.lock();
        try {
            if (this.slotState != SlotState.FREE) return false;
            this.vehicle = vehicle;
            this.slotState = SlotState.OCCUPIED;
            return true;
        } finally {
            mtx.unlock();
        }
    }

    /**
     * Free the slot only if the provided vehicle matches the occupant.
     * Returns true if freed, false otherwise.
     */
    public boolean freeVehicle(Vehicle vehicle) {
        if (vehicle == null) return false;
        mtx.lock();
        try {
            if (this.vehicle == null) return false;
            String currPlate = this.vehicle.getPlateNo();
            if (currPlate == null) return false;
            if (!currPlate.equals(vehicle.getPlateNo())) return false;
            this.vehicle = null;
            this.slotState = SlotState.FREE;
            return true;
        } finally {
            mtx.unlock();
        }
    }

    /**
     * Thread-safe read of state.
     */
    public SlotState getSlotState() {
        mtx.lock();
        try {
            return this.slotState;
        } finally {
            mtx.unlock();
        }
    }

    @Override
    public String toString() {
        return "Slot{" + id + "," + slotType + "," + slotState + "}";
    }
}

/* -------------------- ParkingFloor -------------------- */
/**
 * Represents a floor: map SlotType -> thread-safe list of ParkingSlot.
 * CopyOnWriteArrayList chosen for safe iteration and infrequent writes.
 */
class ParkingFloor {
    private final int floorNo;
    private final ConcurrentHashMap<SlotType, CopyOnWriteArrayList<ParkingSlot>> parkingSlotsMap;

    public ParkingFloor(int floorNo) {
        this.floorNo = floorNo;
        this.parkingSlotsMap = new ConcurrentHashMap<>();
    }

    /**
     * Construct from a map; copies incoming lists defensively.
     */
    public ParkingFloor(Map<SlotType, List<ParkingSlot>> slotMap, int floorNo) {
        this(floorNo);
        if (slotMap != null) {
            for (Map.Entry<SlotType, List<ParkingSlot>> e : slotMap.entrySet()) {
                List<ParkingSlot> incoming = e.getValue() == null ? Collections.emptyList() : e.getValue();
                this.parkingSlotsMap.put(e.getKey(), new CopyOnWriteArrayList<>(incoming));
            }
        }
    }

    public int getFloorNo() { return floorNo; }

    /**
     * Add slots for a type. computeIfAbsent ensures a list exists.
     */
    public void addParkingSlot(SlotType slotType, List<ParkingSlot> parkingSlotList) {
        if (parkingSlotList == null || parkingSlotList.isEmpty()) return;
        this.parkingSlotsMap
                .computeIfAbsent(slotType, k -> new CopyOnWriteArrayList<>())
                .addAll(new ArrayList<>(parkingSlotList)); // defensive copy
    }

    public void removeParkingSlot(SlotType slotType, List<ParkingSlot> parkingSlotList) {
        if (parkingSlotList == null || parkingSlotList.isEmpty()) return;
        CopyOnWriteArrayList<ParkingSlot> list = parkingSlotsMap.get(slotType);
        if (list != null) list.removeAll(parkingSlotList);
    }

    /**
     * Snapshot view of slots of a type.
     */
    public List<ParkingSlot> getSlotByType(SlotType slotType) {
        CopyOnWriteArrayList<ParkingSlot> list = parkingSlotsMap.get(slotType);
        if (list == null) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    /**
     * Return free slots for a type (safe to iterate).
     */
    public List<ParkingSlot> getFreeSlots(SlotType slotType) {
        List<ParkingSlot> freeSlots = new ArrayList<>();
        CopyOnWriteArrayList<ParkingSlot> list = parkingSlotsMap.get(slotType);
        if (list == null) return freeSlots;
        for (ParkingSlot s : list) {
            if (s.getSlotState() == SlotState.FREE) freeSlots.add(s);
        }
        return freeSlots;
    }
}

/* -------------------- Ticket & Receipt -------------------- */
class ParkingTicket {
    private final String ticketId;
    private final Vehicle vehicle;
    private final ParkingSlot slot;
    private final LocalDateTime startTime;
    private LocalDateTime endTime;
    private TicketStatus status;

    public ParkingTicket(String ticketId, Vehicle vehicle, ParkingSlot slot) {
        this.ticketId = ticketId;
        this.vehicle = vehicle;
        this.slot = slot;
        this.startTime = LocalDateTime.now();
        this.status = TicketStatus.OPEN;
    }

    public String getTicketId() { return ticketId; }
    public Vehicle getVehicle() { return vehicle; }
    public ParkingSlot getSlot() { return slot; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public TicketStatus getStatus() { return status; }

    public void closeTicket() {
        if (this.status == TicketStatus.CLOSED) return;
        this.status = TicketStatus.CLOSED;
        this.endTime = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "Ticket{" + ticketId + ", slot=" + slot.getId() + ", start=" + startTime + ", status=" + status + "}";
    }
}

class ParkingReceipt {
    private final String id;
    private final ParkingTicket ticket;
    private final double price;
    private final LocalDateTime time;

    public ParkingReceipt(String id, ParkingTicket ticket, double price) {
        this.id = id;
        this.ticket = ticket;
        this.price = price;
        this.time = LocalDateTime.now();
    }

    @Override
    public String toString() {
        long mins = ticket.getEndTime() == null ? 0 :
                Duration.between(ticket.getStartTime(), ticket.getEndTime()).toMinutes();
        return "Receipt{" + id + ", price=" + price + ", mins=" + mins + ", plate=" + ticket.getVehicle().getPlateNo() + "}";
    }
}

/* -------------------- Strategy & Pricing -------------------- */
interface ParkingStrategy {
    /**
     * Suggest a candidate slot for the given vehicle on this floor.
     * Returns null if none available.
     * Note: this only suggests - caller must call allocateVehicle() to atomically claim it.
     */
    ParkingSlot getParkingSlot(Vehicle vehicle, ParkingFloor parkingFloor);
}

/**
 * Nearest strategy: pick the first free slot of the matching type.
 */
class NearestParkingStrategy implements ParkingStrategy {
    @Override
    public ParkingSlot getParkingSlot(Vehicle vehicle, ParkingFloor pf) {
        if (vehicle == null || pf == null) return null;
        SlotType slotType = SlotType.valueOf(vehicle.getVehicleType().name());
        List<ParkingSlot> free = pf.getFreeSlots(slotType);
        if (free.isEmpty()) return null;
        return free.get(0);
    }
}

interface PricingStrategy {
    double getPrice(ParkingTicket ticket);
}

/**
 * Duration-based pricing: hourly rates per vehicle type, rounding up to nearest hour, min 1 hour.
 */
class DurationBasedPricingStrategy implements PricingStrategy {
    private final Map<VehicleType, Double> ratesPerHour;

    public DurationBasedPricingStrategy(Map<VehicleType, Double> ratesPerHour) {
        this.ratesPerHour = new EnumMap<>(ratesPerHour);
    }

    @Override
    public double getPrice(ParkingTicket ticket) {
        if (ticket == null) return 0.0;
        if (ticket.getStatus() == TicketStatus.OPEN) {
            ticket.closeTicket();
        }
        LocalDateTime end = ticket.getEndTime();
        LocalDateTime start = ticket.getStartTime();
        if (end == null || start == null) return 0.0;
        long minutes = Duration.between(start, end).toMinutes();
        double hours = Math.max(1.0, Math.ceil(minutes / 60.0));
        return ratesPerHour.getOrDefault(ticket.getVehicle().getVehicleType(), 50.0) * hours;
    }
}

/* -------------------- ParkingLot (orchestrator) -------------------- */
/**
 * Main orchestrator. Uses:
 *  - parkingStrategy for slot selection
 *  - per-slot atomic allocate/free
 *  - bounded retries (MAX_RETRIES) to handle races
 */
public class ParkingLot {
    private static ParkingLot instance;
    private static final Lock instanceLock = new ReentrantLock();

    private final ConcurrentHashMap<Integer, ParkingFloor> parkingFloors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ParkingTicket> parkingTickets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ParkingReceipt> parkingReceipts = new ConcurrentHashMap<>();
    private final ParkingStrategy parkingStrategy;
    private final PricingStrategy pricingStrategy;

    // Bound retries for parking attempts
    private static final int MAX_RETRIES = 3;
    // small pause between retries (ms)
    private static final long RETRY_PAUSE_MS = 10L;

    private ParkingLot(Map<Integer, ParkingFloor> parkingFloorMap,
                        ParkingStrategy parkingStrategy,
                        PricingStrategy pricingStrategy) {
        if (parkingFloorMap != null) {
            for (Map.Entry<Integer, ParkingFloor> e : parkingFloorMap.entrySet()) {
                this.parkingFloors.put(e.getKey(), e.getValue());
            }
        }
        this.parkingStrategy = parkingStrategy;
        this.pricingStrategy = pricingStrategy;
    }

    /**
     * Singleton creator: first call initializes with provided params; subsequent calls return the same instance.
     */
    public static ParkingLot getInstance(Map<Integer, ParkingFloor> parkingFloorMap,
                                          ParkingStrategy parkingStrategy,
                                          PricingStrategy pricingStrategy) {
        instanceLock.lock();
        try {
            if (instance == null) {
                instance = new ParkingLot(parkingFloorMap, parkingStrategy, pricingStrategy);
            }
            return instance;
        } finally {
            instanceLock.unlock();
        }
    }

    public void addFloor(int floorNo, ParkingFloor floor) {
        parkingFloors.put(floorNo, floor);
    }

    /**
     * Park a vehicle on a specific floor using the configured parkingStrategy.
     * Attempts up to MAX_RETRIES if candidate allocation loses a race.
     * Returns a ticket on success, or null if none allocated.
     */
    public ParkingTicket parkVehicle(Vehicle vehicle, Integer floorNo) {
        if (vehicle == null || floorNo == null) return null;
        ParkingFloor pf = parkingFloors.get(floorNo);
        if (pf == null) return null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            ParkingSlot candidate = parkingStrategy.getParkingSlot(vehicle, pf);
            if (candidate == null) return null; // no candidate at this moment

            if (candidate.allocateVehicle(vehicle)) {
                String ticketId = UUID.randomUUID().toString();
                ParkingTicket t = new ParkingTicket(ticketId, vehicle, candidate);
                parkingTickets.put(ticketId, t);
                return t;
            }

            // allocation lost due to contention; optionally pause before retrying
            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(RETRY_PAUSE_MS);
                } catch (InterruptedException ignored) {

                }
            }
        }
        // after retries, return null
        return null;
    }

    /**
     * Auto search across floors and attempt bounded allocation on each floor.
     * Returns ticket on success, or null if none allocated.
     */
    public ParkingTicket parkVehicleAuto(Vehicle vehicle) {
        if (vehicle == null) return null;

        // Iterate floors (could be ordered by preference)
        for (ParkingFloor pf : parkingFloors.values()) {
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                ParkingSlot candidate = parkingStrategy.getParkingSlot(vehicle, pf);
                if (candidate == null) break; // no candidate on this floor, try next floor

                if (candidate.allocateVehicle(vehicle)) {
                    String ticketId = UUID.randomUUID().toString();
                    ParkingTicket t = new ParkingTicket(ticketId, vehicle, candidate);
                    parkingTickets.put(ticketId, t);
                    return t;
                }

                if (attempt < MAX_RETRIES) {
                    try { Thread.sleep(RETRY_PAUSE_MS); } catch (InterruptedException ignored) {}
                }
            }
            // try next floor if not allocated on this one
        }
        return null;
    }

    /**
     * Unpark by ticket id; returns receipt or null on failure.
     */
    public ParkingReceipt unParkVehicleByTicketId(String ticketId) {
        if (ticketId == null) return null;
        ParkingTicket ticket = parkingTickets.get(ticketId);
        if (ticket == null) return null;
        return unParkVehicle(ticket);
    }

    /**
     * Unpark a vehicle using ticket object.
     */
    public ParkingReceipt unParkVehicle(ParkingTicket ticket) {
        if (ticket == null) return null;
        ParkingSlot slot = ticket.getSlot();
        Vehicle vehicle = ticket.getVehicle();

        boolean freed = slot.freeVehicle(vehicle);
        if (!freed) {
            return null; // could not free (mismatch or already free)
        }

        ticket.closeTicket();
        double price = pricingStrategy.getPrice(ticket);
        String receiptId = UUID.randomUUID().toString();
        ParkingReceipt receipt = new ParkingReceipt(receiptId, ticket, price);
        parkingReceipts.put(receiptId, receipt);
        parkingTickets.remove(ticket.getTicketId());
        return receipt;
    }

    public List<ParkingSlot> getFreeSlots(SlotType slotType, Integer floorNo) {
        ParkingFloor floor = parkingFloors.get(floorNo);
        if (floor == null) return Collections.emptyList();
        return floor.getFreeSlots(slotType);
    }

    /* -------------------- Demo -------------------- */
    public static void main(String[] args) throws InterruptedException {
        // prepare floors and slots
        Map<SlotType, List<ParkingSlot>> slotsFloor1 = new HashMap<>();
        slotsFloor1.put(SlotType.CAR, Arrays.asList(new ParkingSlot("F1-C1", SlotType.CAR),
                new ParkingSlot("F1-C2", SlotType.CAR)));
        slotsFloor1.put(SlotType.BIKE, Arrays.asList(new ParkingSlot("F1-B1", SlotType.BIKE)));

        ParkingFloor floor1 = new ParkingFloor(slotsFloor1, 1);

        Map<SlotType, List<ParkingSlot>> slotsFloor2 = new HashMap<>();
        slotsFloor2.put(SlotType.CAR, Arrays.asList(new ParkingSlot("F2-C1", SlotType.CAR)));
        ParkingFloor floor2 = new ParkingFloor(slotsFloor2, 2);

        Map<Integer, ParkingFloor> floorMap = new HashMap<>();
        floorMap.put(1, floor1);
        floorMap.put(2, floor2);

        ParkingStrategy strategy = new NearestParkingStrategy();
        Map<VehicleType, Double> rates = Map.of(VehicleType.CAR, 20.0, VehicleType.BIKE, 5.0, VehicleType.TRUCK, 50.0);
        PricingStrategy pricing = new DurationBasedPricingStrategy(rates);

        ParkingLot lot = ParkingLot.getInstance(floorMap, strategy, pricing);

        // Vehicles
        Vehicle car1 = new Vehicle("MH12AB1234", VehicleType.CAR, "Abhishek");
        Vehicle bike1 = new Vehicle("MH12BIKE1", VehicleType.BIKE, "Rahul");

        // Park concurrently to validate bounded retry logic
        Thread t1 = new Thread(() -> {
            ParkingTicket ticket = lot.parkVehicleAuto(car1);
            if (ticket != null) {
                System.out.println("Parked car ticket: " + ticket);
                try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                ParkingReceipt r = lot.unParkVehicle(ticket);
                if (r != null) System.out.println("Receipt: " + r);
                else System.out.println("Failed to unpark car");
            } else {
                System.out.println("No slot for car");
            }
        });

        Thread t2 = new Thread(() -> {
            ParkingTicket ticket = lot.parkVehicleAuto(bike1);
            if (ticket != null) {
                System.out.println("Parked bike ticket: " + ticket);
                try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                ParkingReceipt r = lot.unParkVehicle(ticket);
                if (r != null) System.out.println("Receipt: " + r);
                else System.out.println("Failed to unpark bike");
            } else {
                System.out.println("No slot for bike");
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // Show free car slots on floor 1
        List<ParkingSlot> freeCars = lot.getFreeSlots(SlotType.CAR, 1);
        System.out.println("Free CAR slots on floor1: " + freeCars);
    }
}
