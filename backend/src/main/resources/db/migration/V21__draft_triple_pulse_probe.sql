-- #145: keep first skill-targeted 3/8 technical probe unassignable until pedagogical,
-- notation and playable-audio review. No retroactive changes to assigned GM-EB01.
INSERT INTO exercises
 (id,code,version,is_current,title,instrument_code,technique_code,style_code,difficulty,
  bpm,measures,meter,author,license,review_status,description,
  score_resource,events_resource,content_kind,tempo_unit,duration_target_seconds,
  learning_goal,practice_instructions,tuning)
VALUES
 ('58430000-0000-4000-8000-000000001451','GM-DIAG-PULSE-3-8-01','0.1.0',FALSE,
  'Трёхдольный пульс — короткая проба',
  'ACOUSTIC_GUITAR','FINGERSTYLE','TECHNICAL','BEGINNER',
  100,12,'3/8','Guitar Mentor AI','RIGHTS_PENDING','PENDING_PEDAGOGICAL_REVIEW',
  '21,6 секунды: тройки ровных восьмых на открытых струнах G, B и E.',
  'exercises/GM-DIAG-PULSE-3-8-01/score.musicxml',
  'exercises/GM-DIAG-PULSE-3-8-01/score_events.json',
  'DIAGNOSTIC_PROBE','eighth',22,
  'Наблюдать ровный счёт 1-2-3, акцент первой восьмой и координацию i-m-a.',
  'Прослушай пример; отсчитай три вступительные восьмые и сыграй 12 тактов ровно.',
  'E2 A2 D3 G3 B3 E4');
INSERT INTO exercise_skills (exercise_id,skill_code,target_role,target_level) VALUES
 ('58430000-0000-4000-8000-000000001451','PULSE','PRIMARY',1),
 ('58430000-0000-4000-8000-000000001451','RH_HAND','SUPPORT',1);
INSERT INTO exercise_criteria
 (exercise_id,criterion_code,skill_code,label,observable_result,display_order)
VALUES
 ('58430000-0000-4000-8000-000000001451','TRIPLE_PULSE','PULSE',
  'Ровный трёхдольный пульс',
  '12 групп по три восьмых в равном темпе, акцент на первой восьмой; без остановки',
  10);
-- No MP3/PDF are registered by this migration. Their review previews are
-- external to the application until the approved, version-matched release.
