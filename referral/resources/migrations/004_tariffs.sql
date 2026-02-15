-- Phase 4: Тарифы (посуточно/помесячно), rental_end_date
-- bike.monthly_rate добавляется в db.clj при пересоздании таблицы

-- Тип аренды и дата окончания в booking
ALTER TABLE booking ADD COLUMN rental_type TEXT DEFAULT 'daily';

ALTER TABLE booking ADD COLUMN rental_end_date TEXT;
