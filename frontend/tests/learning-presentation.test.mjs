import test from 'node:test';
import assert from 'node:assert/strict';
import {
 isTaskState, safeTaskStateKey, safeRecommendationKey, safeSourceKey,
 safeCriterionStatusKey, safeOutcomeKey, localDate, validSelectedAssessment
} from '../src/entities/attempt/learning-presentation.ts';

test('#152 trusted task states have safe neutral fallback', () => {
 for (const state of ['ACTIVE','COMPLETED_PASSED','COMPLETED_BY_LEARNER']) {
   assert.equal(isTaskState(state),true);
   assert.equal(safeTaskStateKey(state),'learning.state.'+state);
 }
 for (const state of [null,'FAILED','COMPLETED_UNKNOWN','<img src=x>',undefined]) {
   assert.equal(isTaskState(state),false);
   assert.equal(safeTaskStateKey(state),'learning.unavailable');
 }
});
test('#152 recommendation and provenance cannot infer AI from unknown codes', () => {
 assert.equal(safeRecommendationKey('PASS'),'learning.recommendation.PASS');
 assert.equal(safeRecommendationKey('REVISE'),'learning.recommendation.REVISE');
 assert.equal(safeRecommendationKey('UNCERTAIN'),'learning.recommendation.UNCERTAIN');
 assert.equal(safeRecommendationKey('UNKNOWN'),'learning.unknownRecommendation');
 assert.equal(safeSourceKey('HUMAN_REVIEW'),'learning.sourceHuman');
 assert.equal(safeSourceKey('VALIDATED_MEASUREMENT'),'learning.sourceValidated');
 assert.equal(safeSourceKey('LLM_GENERATED'),'learning.sourceUnknown');
 assert.equal(safeCriterionStatusKey('UNCERTAIN'),'learning.criterionUncertain');
 assert.equal(safeCriterionStatusKey('ASSESSED'),'learning.criterionAssessed');
 assert.equal(safeCriterionStatusKey('NOT_ASSESSED'),'learning.notAssessed');
 assert.equal(safeCriterionStatusKey('OTHER'),'learning.unknown');
 assert.equal(safeOutcomeKey(null),'learning.notAssessed');
 assert.equal(safeOutcomeKey('MEETS'),'learning.meets');
});
test('#152 exact selected assessment cannot borrow from latest attempt', () => {
 const assessment={id:'assess-B',attemptId:'B'};
 assert.equal(validSelectedAssessment(assessment,'A'),false);
 assert.equal(validSelectedAssessment(assessment,'B'),true);
 assert.equal(validSelectedAssessment(null,'B'),false);
});
test('#152 invalid dates neutral, valid dates use the active locale', () => {
 assert.equal(localDate('not-a-date','ru'),'—');
 assert.equal(localDate(null,'en'),'—');
 const ru=localDate('2026-10-25T12:00:00Z','ru');
 const en=localDate('2026-10-25T12:00:00Z','en');
 assert.notEqual(ru,en);
 assert.ok(ru.length>3 && en.length>3);
});

test('#152 attempt selector has a separate, accessible full-width row', async () => {
 const { readFile } = await import('node:fs/promises');
 const view = await readFile(new URL('../src/entities/attempt/LearningResultsPanel.vue', import.meta.url), 'utf8');
 assert.match(view, /<div class="learning-attempt-picker">\s*<label for="learning-attempt">/);
 assert.match(view, /<select id="learning-attempt"[^>]+>/);
 assert.match(view, /\.learning-attempt-picker\s*\{[^}]*flex-direction:column;[^}]*gap:8px/);
 assert.match(view, /\.learning-attempt-picker select\s*\{[^}]*width:100%;[^}]*min-height:44px/);
});
