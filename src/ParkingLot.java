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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

// enums (unchanged)
enum SlotType { CAR, BIKE, TRUCK }
enum VehicleType { CAR, BIKE, TRUCK }
enum SlotStatus { OCCUPIED, VACANT }
enum TicketStatus { OPEN, CLOSED }

// Slot with per-slot ReentrantLock
class Slot {
    private final String id;
    private final SlotType type;
    private SlotStatus status;
    private Vehicle occupant;
    private final Lock mtx = new ReentrantLock();

    public Slot(String id, SlotType type) {
        this.id = id;
        this.type = type;
        this.status = SlotStatus.VACANT;
        this.occupant = null;
    }

    public String getId() { return id; }
    public SlotType getType() { return type; }

    public SlotStatus getStatus() {
        mtx.lock();
        try { return status; }
        finally { mtx.unlock(); }
    }

    // Try to assign; returns true if success. Always unlocks.
    public boolean assign(Vehicle v) {
        mtx.lock();
        try {
            if (this.status != SlotStatus.VACANT) return false;
            this.occupant = v;
            this.status = SlotStatus.OCCUPIED;
            return true;
        } finally {
            mtx.unlock();
        }
    }

    // Unassign only if currently occupied (idempotent)
    public boolean unassign() {
        mtx.lock();
        try {
            if (this.status == SlotStatus.VACANT) return false;
            this.status = SlotStatus.VACANT;
            this.occupant = null;
            return true;
        } finally {
            mtx.unlock();
        }
    }
}

// Vehicle (unchanged)
class Vehicle {
    private final String plateNo;
    private final VehicleType type;
    public Vehicle(String plateNo, VehicleType type) {
        this.plateNo = plateNo;
        this.type = type;
    }
    public String getPlateNo() { return plateNo; }
    public VehicleType getType() { return type; }
}

// Ticket
class ParkingTicket {
    private final String id;
    private final Vehicle vehicle;
    private final Slot slot;
    private final int floorNo;
    private final LocalDateTime startTime;
    private LocalDateTime endTime;
    private TicketStatus status;

    public ParkingTicket(String id, Vehicle vehicle, Slot slot, int floorNo) {
        this.id = id;
        this.vehicle = vehicle;
        this.slot = slot;
        this.floorNo = floorNo;
        this.startTime = LocalDateTime.now();
        this.status = TicketStatus.OPEN;
    }

    public void closeTicket() {
        this.status = TicketStatus.CLOSED;
        this.endTime = LocalDateTime.now();
    }

    public String getId() { return id; }
    public Vehicle getVehicle() { return vehicle; }
    public Slot getSlot() { return slot; }
    public int getFloorNo() { return floorNo; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public TicketStatus getStatus() { return status; }
}

// Receipt
class ParkingReceipt {
    private final String id;
    private final ParkingTicket ticket;
    private final double price;
    public ParkingReceipt(String id, ParkingTicket ticket, double price) {
        this.id = id;
        this.ticket = ticket;
        this.price = price;
    }
    @Override
    public String toString() {
        long minutes = Duration.between(ticket.getStartTime(), ticket.getEndTime()).toMinutes();
        return "Receipt{id=" + id + ", ticket=" + ticket.getId() + ", plate=" + ticket.getVehicle().getPlateNo()
                + ", slot=" + ticket.getSlot().getId() + ", floor=" + ticket.getFloorNo()
                + ", price=" + price + ", durationMins=" + minutes + "}";
    }
}

// ParkingFloor
class ParkingFloor {
    private final int floor;
    private final Map<SlotType, List<Slot>> slotMap = new HashMap<>();

    public ParkingFloor(int floor) { this.floor = floor; }

    public int getFloor() { return floor; }

    public void addSlots(List<Slot> slots, SlotType type) {
        slotMap.computeIfAbsent(type, k -> new ArrayList<>()).addAll(slots);
    }

    public List<Slot> getSlots(SlotType type) {
        return slotMap.getOrDefault(type, Collections.emptyList());
    }
}

// Strategy (unchanged)
interface ParkingStrategy {
    Optional<Slot> findSlot(Vehicle vehicle, ParkingFloor floor);
}

class NearestParkingStrategy implements ParkingStrategy {
    @Override
    public Optional<Slot> findSlot(Vehicle vehicle, ParkingFloor floor) {
        SlotType want = SlotType.valueOf(vehicle.getType().name());
        for (Slot s : floor.getSlots(want)) {
            if (s.getStatus() == SlotStatus.VACANT) return Optional.of(s);
        }
        return Optional.empty();
    }
}

// ParkingLot: uses ReentrantLock for critical ops and concurrent maps
public class ParkingLot {
    private static ParkingLot instance;
    private static final Lock instanceLock = new ReentrantLock();

    private final String id;
    private final String name;
    private final ParkingStrategy strategy;
    private final ConcurrentHashMap<Integer, ParkingFloor> floorMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ParkingTicket> activeTickets = new ConcurrentHashMap<>();
    private final Lock lotLock = new ReentrantLock(true);

    // simple rates
    private final Map<VehicleType, Double> rates = Map.of(
            VehicleType.CAR, 40.0, VehicleType.BIKE, 10.0, VehicleType.TRUCK, 80.0
    );

    private ParkingLot(String id, String name) {
        this.id = id;
        this.name = name;
        this.strategy = new NearestParkingStrategy();
    }

    public static ParkingLot getInstance(String id, String name) {
        instanceLock.lock();
        try {
            if (instance == null) instance = new ParkingLot(id, name);
            return instance;
        } finally {
            instanceLock.unlock();
        }
    }

    public void addFloor(ParkingFloor floor) { floorMap.put(floor.getFloor(), floor); }

    // Park on specified floor (coarse-grained locking to prevent races)
    public ParkingTicket parkVehicleOnFloor(Vehicle v, int floorNo) {
        lotLock.lock();
        try {
            ParkingFloor floor = floorMap.get(floorNo);
            if (floor == null) throw new IllegalArgumentException("Floor not found: " + floorNo);

            Optional<Slot> opt = strategy.findSlot(v, floor);
            if (opt.isEmpty()) throw new IllegalStateException("No slot available for vehicle on floor " + floorNo);

            Slot s = opt.get();
            // try assign (slot-level lock ensures correctness); if fails -> treat as contention
            if (!s.assign(v)) throw new IllegalStateException("Slot allocation contention, try again");

            String ticketId = UUID.randomUUID().toString();
            ParkingTicket ticket = new ParkingTicket(ticketId, v, s, floorNo);
            activeTickets.put(ticketId, ticket);
            System.out.println("Parked " + v.getPlateNo() + " on slot " + s.getId());
            return ticket;
        } finally {
            lotLock.unlock();
        }
    }

    // Auto-search across floors (common interview ask)
    public ParkingTicket parkVehicleAuto(Vehicle v) {
        lotLock.lock();
        try {
            for (ParkingFloor floor : floorMap.values()) {
                Optional<Slot> opt = strategy.findSlot(v, floor);
                if (opt.isPresent()) {
                    Slot s = opt.get();
                    if (!s.assign(v)) continue; // someone else raced — continue searching
                    String ticketId = UUID.randomUUID().toString();
                    ParkingTicket ticket = new ParkingTicket(ticketId, v, s, floor.getFloor());
                    activeTickets.put(ticketId, ticket);
                    System.out.println("Auto parked " + v.getPlateNo() + " on slot " + s.getId());
                    return ticket;
                }
            }
            throw new IllegalStateException("No slots available in any floor for " + v.getType());
        } finally {
            lotLock.unlock();
        }
    }

    // Unpark by ticket id
    public ParkingReceipt unparkByTicketId(String ticketId) {
        lotLock.lock();
        try {
            ParkingTicket t = activeTickets.get(ticketId);
            if (t == null) throw new IllegalArgumentException("Ticket not found: " + ticketId);
            return unparkTicketInternal(t);
        } finally {
            lotLock.unlock();
        }
    }

    // internal helper (assumes lotLock held)
    private ParkingReceipt unparkTicketInternal(ParkingTicket t) {
        if (t.getStatus() == TicketStatus.CLOSED)
            throw new IllegalStateException("Ticket already closed: " + t.getId());

        t.closeTicket();
        Slot s = t.getSlot();
        boolean freed = s.unassign(); // idempotent
        if (!freed) System.out.println("Warning: slot was already vacant for " + s.getId());

        activeTickets.remove(t.getId());

        long mins = Duration.between(t.getStartTime(), t.getEndTime()).toMinutes();
        double hours = Math.ceil(mins / 60.0);
        double rate = rates.getOrDefault(t.getVehicle().getType(), 50.0);
        double price = rate * (hours <= 0 ? 1 : hours);

        ParkingReceipt receipt = new ParkingReceipt(UUID.randomUUID().toString(), t, price);
        System.out.println("Unparked " + t.getVehicle().getPlateNo() + " price=" + price);
        return receipt;
    }

    // Query availability on a floor/type
    public int availableSlots(int floorNo, SlotType type) {
        ParkingFloor f = floorMap.get(floorNo);
        if (f == null) return 0;
        int cnt = 0;
        for (Slot s : f.getSlots(type)) if (s.getStatus() == SlotStatus.VACANT) cnt++;
        return cnt;
    }

    // Demo main
    public static void main(String[] args) throws InterruptedException {
        ParkingLot lot = ParkingLot.getInstance("p1", "MyLot");

        ParkingFloor f1 = new ParkingFloor(1);
        f1.addSlots(Arrays.asList(new Slot("F1-C1", SlotType.CAR), new Slot("F1-C2", SlotType.CAR)), SlotType.CAR);
        f1.addSlots(Arrays.asList(new Slot("F1-B1", SlotType.BIKE)), SlotType.BIKE);
        lot.addFloor(f1);

        ParkingFloor f2 = new ParkingFloor(2);
        f2.addSlots(Arrays.asList(new Slot("F2-C1", SlotType.CAR)), SlotType.CAR);
        lot.addFloor(f2);

        Vehicle car = new Vehicle("UP65AB1234", VehicleType.CAR);
        Vehicle bike = new Vehicle("UP65XY9876", VehicleType.BIKE);

        // park automatically (search floors)
        ParkingTicket t1 = lot.parkVehicleAuto(car);
        ParkingTicket t2 = lot.parkVehicleAuto(bike);

        Thread.sleep(1500);

        ParkingReceipt r1 = lot.unparkByTicketId(t1.getId());
        System.out.println(r1);
    }
}
