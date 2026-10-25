package ai.guitarmentor.application.diagnostic;

import ai.guitarmentor.application.exercise.ExercisePublicationPolicy;
import ai.guitarmentor.application.exercise.ExerciseViewAssembler;
import ai.guitarmentor.application.learning.LearningProblem;
import ai.guitarmentor.infrastructure.persistence.*;
import ai.guitarmentor.domain.diagnostic.*;
import ai.guitarmentor.interfaces.rest.diagnostic.DiagnosticDtos.SessionView;
import ai.guitarmentor.interfaces.rest.diagnostic.TaskDtos.TaskView;
import jakarta.persistence.EntityNotFoundException;
import java.sql.Timestamp;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NextLessonService {
    private final LearnerRepository learners;
    private final DiagnosticSessionRepository sessions;
    private final DiagnosticTaskRepository tasks;
    private final ExerciseRepository exercises;
    private final LearningRepository learning;
    private final ExerciseViewAssembler assembler;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public NextLessonService(LearnerRepository learners, DiagnosticSessionRepository sessions,
            DiagnosticTaskRepository tasks, ExerciseRepository exercises, LearningRepository learning,
            ExerciseViewAssembler assembler, JdbcTemplate jdbc,
            @Value("${guitarmentor.learning.enabled:false}") boolean enabled) {
        this.learners=learners; this.sessions=sessions; this.tasks=tasks;
        this.exercises=exercises; this.learning=learning; this.assembler=assembler;
        this.jdbc=jdbc; this.enabled=enabled;
    }

    public record Request(UUID requestId, Long expectedVersion, String exerciseCode, String exerciseVersion) {}
    public record Result(UUID transitionId, UUID sourceSessionId, UUID sourceTaskId,
                         SessionView session, TaskView task) {}
    public record Created(Result result, boolean replay) {}
    private record Transition(UUID id, UUID source, UUID target, String code, String version,
                              long expectedVersion) {}
    public record HistoryItem(UUID sessionId, UUID taskId, String exerciseCode, String exerciseVersion,
                              String title, Instant assignedAt, String taskState, boolean isCurrent) {}
    public record HistoryPage(List<HistoryItem> items, String nextCursor) {}

    private LearningProblem problem(HttpStatus status, String code) { return new LearningProblem(status,code); }

    private Optional<Transition> transitionByRequest(UUID learner, UUID key) {
        return jdbc.query("""
            SELECT id,source_task_id,target_task_id,requested_exercise_code,
                   requested_exercise_version,expected_source_version
            FROM lesson_transitions WHERE learner_id=? AND request_id=?
            """, (rs,n)->new Transition(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),rs.getLong(6)),
                learner,key).stream().findFirst();
    }
    private Optional<Transition> transitionBySource(UUID source) {
        return jdbc.query("""
            SELECT id,source_task_id,target_task_id,requested_exercise_code,
                   requested_exercise_version,expected_source_version
            FROM lesson_transitions WHERE source_task_id=?
            """,(rs,n)->new Transition(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),rs.getLong(6)),
                source).stream().findFirst();
    }
    private boolean same(Transition t, UUID source, Request r) {
        return t.source().equals(source) && t.code().equals(r.exerciseCode())
            && t.version().equals(r.exerciseVersion()) && t.expectedVersion()==r.expectedVersion();
    }
    private Result view(Transition tr) {
        var target=tasks.findById(tr.target()).orElseThrow();
        return new Result(tr.id(),tasks.findById(tr.source()).orElseThrow().getSession().getId(),
            tr.source(),DiagnosticSessionService.toView(target.getSession()),
            TaskView.from(target,assembler.one(target.getExercise())));
    }
    @Transactional
    public Created start(UUID learnerId, UUID sessionId, UUID taskId, Request request) {
        if (!enabled) throw problem(HttpStatus.SERVICE_UNAVAILABLE,"LEARNING_DISABLED");
        if (request==null || request.requestId()==null || request.expectedVersion()==null
            || request.expectedVersion()<0 || request.exerciseCode()==null
            || !request.exerciseCode().matches("[A-Za-z0-9_-]{1,50}")
            || request.exerciseVersion()==null
            || !request.exerciseVersion().matches("[A-Za-z0-9._-]{1,20}"))
            throw problem(HttpStatus.BAD_REQUEST,"INVALID_NEXT_LESSON_REQUEST");

        // Match the existing start() lock order: learner -> owned session -> task state.
        var learner=learners.findLockedById(learnerId)
            .orElseThrow(()->problem(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND"));
        var source=sessions.findLockedOwnedById(learnerId,sessionId)
            .orElseThrow(()->problem(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND"));
        var task=tasks.findBySession_Id(sessionId)
            .filter(t->t.getId().equals(taskId))
            .orElseThrow(()->problem(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND"));
        var prior=transitionByRequest(learnerId,request.requestId());
        if (prior.isPresent()) {
            if (!same(prior.get(),taskId,request))
                throw problem(HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_REUSED");
            return new Created(view(prior.get()),true);
        }
        var successor=transitionBySource(taskId);
        if (successor.isPresent()) {
            if (same(successor.get(),taskId,request))
                return new Created(view(successor.get()),true);
            throw problem(HttpStatus.CONFLICT,"NEXT_LESSON_ALREADY_EXISTS");
        }
        var state=learning.state(taskId,true)
            .orElseThrow(()->problem(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND"));
        if ("ACTIVE".equals(state.state()))
            throw problem(HttpStatus.CONFLICT,"SOURCE_TASK_NOT_COMPLETED");
        if (state.version()!=request.expectedVersion())
            throw problem(HttpStatus.CONFLICT,"LEARNING_VERSION_CONFLICT");
        if (source.getStatus()!=DiagnosticStatus.TASK_ASSIGNED)
            throw problem(HttpStatus.CONFLICT,"NEXT_LESSON_ALREADY_EXISTS");

        var exercise=exercises.findByCodeAndCurrentTrue(request.exerciseCode())
            .orElseThrow(()->problem(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND"));
        if (!request.exerciseVersion().equals(exercise.getVersion()))
            throw problem(HttpStatus.CONFLICT,"EXERCISE_VERSION_CHANGED");
        var profile=source.getInstrumentProfile();
        if (!"LEARNING_REPERTOIRE".equals(exercise.getContentKind())
            || !ExercisePublicationPolicy.eligible(exercise)
            || !exercise.getInstrumentCode().equals(profile.getInstrumentCode())
            || !exercise.getTechniqueCode().equals(profile.getTechniqueCode()))
            throw problem(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");

        source.archive();
        sessions.saveAndFlush(source); // Free unique active-profile index before INSERT.
        var next=new DiagnosticSession(profile);
        next.prepareNextLearningTask();
        sessions.saveAndFlush(next);
        var nextTask=tasks.saveAndFlush(new DiagnosticTask(next,exercise));
        learning.initialize(nextTask.getId());
        next.markTaskAssigned();
        sessions.saveAndFlush(next);
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO lesson_transitions
            (id,learner_id,instrument_profile_id,request_id,source_task_id,target_task_id,
             requested_exercise_code,requested_exercise_version,expected_source_version,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            """,id,learnerId,profile.getId(),request.requestId(),taskId,nextTask.getId(),
            request.exerciseCode(),request.exerciseVersion(),request.expectedVersion(),
            Timestamp.from(Instant.now()));
        return new Created(new Result(id,sessionId,taskId,
            DiagnosticSessionService.toView(next),TaskView.from(nextTask,assembler.one(exercise))),false);
    }

    @Transactional(readOnly=true)
    public HistoryPage history(UUID learnerId, String instrumentCode, Integer limit, String cursor) {
        if (limit!=null && (limit<1 || limit>50))
            throw problem(HttpStatus.BAD_REQUEST,"INVALID_PAGINATION");
        int size=limit==null?20:limit;
        var learner=learners.findById(learnerId)
            .orElseThrow(()->problem(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND"));
        var profile=learner.getInstrumentProfiles().stream()
            .filter(p->p.getInstrumentCode().equals(instrumentCode)).findFirst()
            .orElseThrow(()->problem(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND"));
        Instant beforeTime=null; UUID beforeId=null;
        if (cursor!=null) {
            if (cursor.isBlank() || cursor.length()>512) throw problem(HttpStatus.BAD_REQUEST,"INVALID_PAGINATION");
            try {
                String raw=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);
                String[] fields=raw.split("\\|",-1);
                if (fields.length!=4 || !fields[0].equals(learnerId.toString())
                    || !fields[1].equals(profile.getId().toString()))
                    throw new IllegalArgumentException();
                beforeTime=Instant.parse(fields[2]); beforeId=UUID.fromString(fields[3]);
            } catch (RuntimeException ex) { throw problem(HttpStatus.BAD_REQUEST,"INVALID_PAGINATION"); }
        }
        String query="""
            SELECT s.id,t.id,e.code,e.version,e.title,t.assigned_at,ls.state,
                   CASE WHEN s.status='ARCHIVED' THEN false ELSE true END
            FROM diagnostic_tasks t
            JOIN diagnostic_sessions s ON s.id=t.diagnostic_session_id
            JOIN exercises e ON e.id=t.exercise_id
            LEFT JOIN task_learning_state ls ON ls.task_id=t.id
            WHERE s.instrument_profile_id=?
            """+(beforeTime==null?"":" AND (t.assigned_at,t.id) < (?,?)")
            +" ORDER BY t.assigned_at DESC,t.id DESC LIMIT ?";
        Object[] args=beforeTime==null?new Object[]{profile.getId(),size+1}
            :new Object[]{profile.getId(),Timestamp.from(beforeTime),beforeId,size+1};
        var all=jdbc.query(query,(rs,n)->new HistoryItem(
            rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
            rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant(),rs.getString(7),
            rs.getBoolean(8)),args);
        var items=all.stream().limit(size).toList();
        String next=null;
        if(all.size()>size) {
            var last=items.getLast();
            next=Base64.getUrlEncoder().withoutPadding().encodeToString(
                (learnerId+"|"+profile.getId()+"|"+last.assignedAt()+"|"+last.taskId())
                .getBytes(StandardCharsets.UTF_8));
        }
        return new HistoryPage(items,next);
    }
}
