-- M7：新增班次“假日值班”，按天排，与按周的值班电话并存（设计 §11）
INSERT INTO shift_type (code, name, start_time, end_time, cross_day, work_hours, counts_as_work, color, sort_order)
VALUES ('H', '假日值班', '08:00', '17:30', FALSE, 8.0, TRUE, '#0f766e', 7);
