-- Two types of transactions: revenue (counted in 20% share) and service (not counted)
ALTER TABLE rental ADD COLUMN transaction_type TEXT DEFAULT 'revenue' CHECK (transaction_type IN ('revenue', 'service'));
