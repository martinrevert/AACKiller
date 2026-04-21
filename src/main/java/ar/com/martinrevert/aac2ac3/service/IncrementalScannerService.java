package ar.com.martinrevert.aac2ac3.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class IncrementalScannerService {
    private static final Logger log = LoggerFactory.getLogger(IncrementalScannerService.class);

    private final IndexerService indexerService;

    @Value("${index.reconcile.enabled:true}")
    private boolean reconcileEnabled;

    private final AtomicBoolean inProgress = new AtomicBoolean(false);

    public IncrementalScannerService(IndexerService indexerService) {
        this.indexerService = indexerService;
    }

    @Scheduled(
            initialDelayString = "${index.reconcile.initialDelayMs:15000}",
            fixedDelayString = "${index.reconcile.intervalMs:300000}"
    )
    public void reconcileIndex() {
        if (!reconcileEnabled) {
            return;
        }
        if (!inProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            indexerService.index();
        } catch (Exception ex) {
            log.warn("Scheduled reconcile failed", ex);
        } finally {
            inProgress.set(false);
        }
    }
}
