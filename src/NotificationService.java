import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;

enum NotificationType { PUSH, EMAIL, SMS }
enum NotificationStatus { DRAFT, SENT, FAILED }

class Recipient {
    private final String recipientId;
    private final String recipientName;
    private String email;
    private String phone;
    private String pushToken;

    Recipient(String recipientId, String recipientName) {
        this.recipientId = recipientId;
        this.recipientName = recipientName;
    }

    public String getRecipientId() { return recipientId; }
    public String getRecipientName() { return recipientName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getPushToken() { return pushToken; }
    public void setPushToken(String pushToken) { this.pushToken = pushToken; }
    public void setEmail(String email) { this.email = email; }
    public void setPhone(String phone) { this.phone = phone; }
}

class Notification {
    private final String id;
    private final String subject;
    private final String message;
    private final NotificationType type;
    private NotificationStatus status;
    private final Recipient recipient;
    private LocalDateTime timeStamp;

    private Notification(Builder builder) {
        this.id = builder.id;
        this.subject = builder.subject;
        this.message = builder.message;
        this.type = builder.type;
        this.recipient = builder.recipient;
        this.status = NotificationStatus.DRAFT;
        this.timeStamp = LocalDateTime.now();
    }

    public String getId() { return id; }
    public String getSubject() { return subject; }
    public String getMessage() { return message; }
    public NotificationType getType() { return type; }
    public NotificationStatus getStatus() { return status; }
    public LocalDateTime getTimeStamp() { return timeStamp; }
    public Recipient getRecipient() { return recipient; }
    public void setStatus(NotificationStatus status) { this.status = status; }

    public static class Builder {
        private final String id;
        private final NotificationType type;
        private String subject;
        private String message;
        private Recipient recipient;

        Builder(NotificationType type, String id) {
            this.id = id;
            this.type = type;
        }

        public Builder withSubject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder withMessage(String message) {
            this.message = message;
            return this;
        }

        public Builder withRecipient(Recipient recipient) {
            this.recipient = recipient;
            return this;
        }

        public Notification build() {
            return new Notification(this);
        }
    }
}

/**
 * ------------- Notification Gateways ---------------
 */
interface NotificationGateway {
    void sendNotification(Notification notification);
}

class SMSGateway implements NotificationGateway {
    @Override
    public void sendNotification(Notification notification) {
        // Simulating a random failure for demonstration purposes
        if (Math.random() < 0.0) { // Set to 0.0 to disable, 1.0 to force fail
            throw new RuntimeException("Simulated SMS Network Error");
        }
        notification.setStatus(NotificationStatus.SENT);
        System.out.println("SMS sent to: " + notification.getRecipient().getPhone());
    }
}

class EmailGateway implements NotificationGateway {
    @Override
    public void sendNotification(Notification notification) {
        notification.setStatus(NotificationStatus.SENT);
        System.out.println("Email sent to: " + notification.getRecipient().getEmail());
    }
}

class PushGateway implements NotificationGateway {
    @Override
    public void sendNotification(Notification notification) {
        notification.setStatus(NotificationStatus.SENT);
        System.out.println("Push sent to: " + notification.getRecipient().getPushToken());
    }
}

// --- UPDATED CLASS: Exponential Backoff Implementation ---
class RetryableNotificationGateway implements NotificationGateway {
    private final NotificationGateway gateway;
    private final int maxRetries;
    private final long baseRetryInterval;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workerPool;

    RetryableNotificationGateway(NotificationGateway gateway, int maxRetries, long baseRetryInterval,
                                 ScheduledExecutorService scheduler, ExecutorService workerPool) {
        this.gateway = gateway;
        this.maxRetries = maxRetries;
        this.baseRetryInterval = baseRetryInterval;
        this.scheduler = scheduler;
        this.workerPool = workerPool;
    }

    @Override
    public void sendNotification(Notification notification) {
        send(notification, 0);
    }

    private void send(Notification notification, int attemptCount) {
        try {
            gateway.sendNotification(notification);
        } catch (Exception ex) {
            int nextAttempt = attemptCount + 1;
            if (nextAttempt > maxRetries) {
                // ... Log failure ...
                notification.setStatus(NotificationStatus.FAILED);
                return;
            }

            long delay = baseRetryInterval * (long) Math.pow(2, attemptCount);

            // OPTIMIZATION: Use scheduler only for timing, run logic on worker pool
            scheduler.schedule(() -> {
                workerPool.submit(() -> send(notification, nextAttempt));
            }, delay, TimeUnit.MILLISECONDS);
        }
    }
}

class NotificationGatewayFactory {
    private static volatile NotificationGatewayFactory instance;
    private static final Lock lock = new ReentrantLock();

    private final Map<NotificationType, NotificationGateway> gateways;

    private NotificationGatewayFactory() {
        gateways = new ConcurrentHashMap<>();
    }

    public static NotificationGatewayFactory getInstance() {
        if (instance == null) {
            lock.lock();
            try {
                if (instance == null) {
                    instance = new NotificationGatewayFactory();
                }
            } finally {
                lock.unlock();
            }
        }
        return instance;
    }

    public NotificationGateway getGateway(NotificationType type) {
        if (!gateways.containsKey(type)) {
            lock.lock();
            try {
                if (!gateways.containsKey(type)) {
                    NotificationGateway gateway;
                    switch (type) {
                        case SMS:   gateway = new SMSGateway(); break;
                        case EMAIL: gateway = new EmailGateway(); break;
                        case PUSH:  gateway = new PushGateway(); break;
                        default:    throw new IllegalArgumentException("Unknown type");
                    }
                    gateways.put(type, gateway);
                }
            } finally {
                lock.unlock();
            }
        }
        return gateways.get(type);
    }

    // Added for testing purposes to inject mock gateways
    public void registerMockGateway(NotificationType type, NotificationGateway gateway) {
        gateways.put(type, gateway);
    }
}

public class NotificationService {
    private static volatile NotificationService instance;
    private static final Lock lock = new ReentrantLock();
    private final ExecutorService executor;
    private final ScheduledExecutorService retryExecutor;
    private final NotificationGatewayFactory gatewayFactory;
    private int maxRetries;
    private Long retryIntervalInMillis;

    private final Map<String, Notification> notificationMap;

    private NotificationService(int threadCount) {
        executor = Executors.newFixedThreadPool(threadCount);
        notificationMap = new ConcurrentHashMap<>();
        gatewayFactory = NotificationGatewayFactory.getInstance();
        maxRetries = 3;
        retryIntervalInMillis = 1000L; // Base interval: 1 second
        retryExecutor = Executors.newScheduledThreadPool(2);
    }

    public static NotificationService getInstance(int threadCount) {
        if (instance == null) {
            lock.lock();
            try {
                if (instance == null) {
                    instance = new NotificationService(threadCount);
                }
            } finally {
                lock.unlock();
            }
        }
        return instance;
    }

    public void sendNotification(Notification notification) {
        notificationMap.put(notification.getId(), notification);
        executor.submit(() -> {
            try {
                NotificationType type = notification.getType();
                NotificationGateway gateway = gatewayFactory.getGateway(type);

                // CORRECTED: Passing 'executor' (workerPool) as the 5th argument
                RetryableNotificationGateway retryableNotificationGateway = new RetryableNotificationGateway(
                        gateway,
                        maxRetries,
                        retryIntervalInMillis,
                        retryExecutor,
                        executor); // <--- Added this!

                retryableNotificationGateway.sendNotification(notification);
            } catch (Exception ex) {
                notification.setStatus(NotificationStatus.FAILED);
                System.err.println("Fatal error sending notification: " + ex.getMessage());
            }
        });
    }

    public NotificationStatus getNotificationStatus(String notificationId) {
        Notification notification = notificationMap.get(notificationId);
        return (notification != null) ? notification.getStatus() : null;
    }

    public void shutdown() {
        executor.shutdown();
        retryExecutor.shutdown();
    }

    // --- MAIN METHOD FOR DEMO ---
    public static void main(String[] args) throws InterruptedException {
        NotificationService service = NotificationService.getInstance(2);

        // 1. Create a mock gateway that fails 2 times then succeeds
        // This lets us see the retries happen
        NotificationGateway flakySMSGateway = new NotificationGateway() {
            private final AtomicInteger attempts = new AtomicInteger(0);
            @Override
            public void sendNotification(Notification notification) {
                int count = attempts.incrementAndGet();
                if (count <= 2) {
                    throw new RuntimeException("Simulated Network Error (Count: " + count + ")");
                }
                notification.setStatus(NotificationStatus.SENT);
                System.out.println("✅ SMS finally sent successfully on attempt " + count);
            }
        };

        // Inject mock into factory
        NotificationGatewayFactory.getInstance().registerMockGateway(NotificationType.SMS, flakySMSGateway);

        // 2. Create Data
        Recipient recipient = new Recipient("101", "John Doe");
        recipient.setPhone("555-0199");

        Notification sms = new Notification.Builder(NotificationType.SMS, "NOTIF-001")
                .withRecipient(recipient)
                .withMessage("Your OTP is 9988")
                .build();

        // 3. Send
        System.out.println("--- Starting Send Process ---");
        service.sendNotification(sms);

        // 4. Keep main thread alive to watch background threads work
        Thread.sleep(10000);
        service.shutdown();
    }
}