package ai.guitarmentor.infrastructure.persistence;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable deletion of one unreferenced library asset and its sanitized derivative. */
@Component
public final class LibraryAssetDeletionWorker {
    private static final Logger LOG=LoggerFactory.getLogger(LibraryAssetDeletionWorker.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final LibraryObjectStore store;
    private final LibraryConversionDeletionGuard conversionGuard;
    private final boolean enabled;

    public LibraryAssetDeletionWorker(JdbcClient jdbc,TransactionTemplate transactions,
            LibraryObjectStore store,LibraryConversionDeletionGuard conversionGuard,
            @Value("${guitar-mentor.library.enabled:false}") boolean enabled) {
        this.jdbc=jdbc; this.transactions=transactions; this.store=store;
        this.conversionGuard=conversionGuard; this.enabled=enabled;
    }

    /** Compatibility constructor for isolated tests. */
    LibraryAssetDeletionWorker(JdbcClient jdbc,TransactionTemplate transactions,
            LibraryObjectStore store,boolean enabled) {
        this(jdbc,transactions,store,new LibraryConversionDeletionGuard(jdbc),enabled);
    }

    @Scheduled(fixedDelayString="${guitar-mentor.library.asset-deletion-delay-ms:15000}")
    public void runScheduled() {
        if(!enabled) return;
        try { processBatch(25); }
        catch(RuntimeException e) { LOG.warn("Library asset deletion deferred: {}",e.getClass().getSimpleName()); }
    }

    public int processBatch(int maximum) {
        if(maximum<1 || maximum>100) throw new IllegalArgumentException("Invalid batch size");
        if(!store.isAvailable()) return 0;
        List<UUID> ids=jdbc.sql("SELECT asset_id FROM library_asset_deletion_jobs WHERE state IN ('PENDING','RETRY') AND next_attempt_at<=now() ORDER BY next_attempt_at,asset_id LIMIT :limit")
            .param("limit",maximum).query(UUID.class).list();
        int completed=0;
        for(UUID id:ids) if(Boolean.TRUE.equals(transactions.execute(status->processOne(id)))) completed++;
        return completed;
    }

    private boolean processOne(UUID assetId) {
        var owners=jdbc.sql("""
            SELECT m.owner_account_id,m.id AS material_id,m.state
            FROM library_assets a JOIN library_materials m ON m.id=a.material_id WHERE a.id=:id
            """).param("id",assetId).query().listOfRows();
        if(owners.isEmpty()) {
            jdbc.sql("DELETE FROM library_asset_deletion_jobs WHERE asset_id=:id").param("id",assetId).update();
            return true;
        }
        var ownerRow=owners.getFirst();
        UUID owner=(UUID)ownerRow.get("owner_account_id"), material=(UUID)ownerRow.get("material_id");
        if(!"READY".equals(ownerRow.get("state"))) return false; // Whole-material worker owns this deletion.
        jdbc.sql("INSERT INTO library_account_usage(account_id) VALUES(:owner) ON CONFLICT DO NOTHING").param("owner",owner).update();
        jdbc.sql("SELECT account_id FROM library_account_usage WHERE account_id=:owner FOR UPDATE").param("owner",owner).query().singleRow();
        var materialRows=jdbc.sql("SELECT state FROM library_materials WHERE id=:id AND owner_account_id=:owner FOR UPDATE")
            .param("id",material).param("owner",owner).query().listOfRows();
        if(materialRows.isEmpty() || !"READY".equals(materialRows.getFirst().get("state"))) return false;
        var jobs=jdbc.sql("SELECT state FROM library_asset_deletion_jobs WHERE asset_id=:id AND next_attempt_at<=now() FOR UPDATE")
            .param("id",assetId).query().listOfRows();
        if(jobs.isEmpty()) return false;
        var assets=jdbc.sql("SELECT object_key,byte_size,preview_object_key,preview_byte_size,processing_state FROM library_assets WHERE id=:id AND material_id=:material FOR UPDATE")
            .param("id",assetId).param("material",material).query().listOfRows();
        if(assets.isEmpty()) return true;
        var asset=assets.getFirst();
        if(!"DELETING".equals(asset.get("processing_state"))) {
            jdbc.sql("DELETE FROM library_asset_deletion_jobs WHERE asset_id=:id").param("id",assetId).update();
            return false;
        }
        if(!conversionGuard.cancelAndWaitForAsset(assetId)) {
            jdbc.sql("UPDATE library_asset_deletion_jobs SET next_attempt_at=now()+interval '10 seconds' WHERE asset_id=:id")
                .param("id",assetId).update();
            return false;
        }
        Long published=jdbc.sql("SELECT count(*) FROM library_publication_assets a JOIN library_publications p ON p.id=a.publication_id WHERE a.asset_id=:id AND p.state='PUBLISHED'")
            .param("id",assetId).query(Long.class).single();
        if(published>0) {
            String restoredState="READY";
            try {
                store.file((String)asset.get("object_key"));
                if(asset.get("preview_object_key")!=null) store.file((String)asset.get("preview_object_key"));
            } catch(java.nio.file.NoSuchFileException missing) { restoredState="REJECTED"; }
            catch(IOException unavailable) {
                jdbc.sql("UPDATE library_asset_deletion_jobs SET state='RETRY',attempts=attempts+1,last_error_code='LIBRARY_STORAGE_UNAVAILABLE',next_attempt_at=now()+interval '1 minute' WHERE asset_id=:id")
                    .param("id",assetId).update();
                return false;
            }
            jdbc.sql("UPDATE library_assets SET processing_state=:state WHERE id=:id")
                .param("state",restoredState).param("id",assetId).update();
            jdbc.sql("DELETE FROM library_asset_deletion_jobs WHERE asset_id=:id").param("id",assetId).update();
            LOG.error("Refused deletion of published library asset {}",assetId);
            return false;
        }
        try {
            String key=(String)asset.get("object_key");
            Long refs=jdbc.sql("SELECT count(*) FROM library_assets WHERE object_key=:key").param("key",key).query(Long.class).single();
            if(refs!=1) throw new IOException("Ambiguous asset object reference");
            store.delete(key);
            String preview=(String)asset.get("preview_object_key");
            if(preview!=null) {
                Long previewRefs=jdbc.sql("SELECT count(*) FROM library_assets WHERE preview_object_key=:key").param("key",preview).query(Long.class).single();
                if(previewRefs!=1) throw new IOException("Ambiguous preview object reference");
                store.delete(preview);
            }
        } catch(IOException|RuntimeException e) {
            jdbc.sql("UPDATE library_asset_deletion_jobs SET state='RETRY',attempts=attempts+1,last_error_code='LIBRARY_DELETE_RETRY',next_attempt_at=now()+least(interval '1 hour',interval '10 seconds'*power(2,least(attempts,8))) WHERE asset_id=:id")
                .param("id",assetId).update();
            LOG.warn("Library asset deletion retry scheduled for {}",assetId);
            return false;
        }
        long bytes=((Number)asset.get("byte_size")).longValue();
        long physicalFiles=1;
        if(asset.get("preview_byte_size")!=null) { bytes+=((Number)asset.get("preview_byte_size")).longValue(); physicalFiles++; }
        // Remove the RESTRICTing queue FK before deleting the asset; both remain in one DB transaction.
        jdbc.sql("DELETE FROM library_asset_deletion_jobs WHERE asset_id=:id").param("id",assetId).update();
        jdbc.sql("DELETE FROM library_assets WHERE id=:id").param("id",assetId).update();
        int updated=jdbc.sql("UPDATE library_account_usage SET charged_bytes=charged_bytes-:bytes,charged_files=charged_files-:files,revision=revision+1 WHERE account_id=:owner AND charged_bytes>=:bytes AND charged_files>=:files")
            .param("bytes",bytes).param("files",physicalFiles).param("owner",owner).update();
        if(updated!=1) throw new IllegalStateException("Library quota invariant violated");
        return true;
    }
}
