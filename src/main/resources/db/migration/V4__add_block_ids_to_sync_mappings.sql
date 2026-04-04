ALTER TABLE sync_mappings 
ADD COLUMN IF NOT EXISTS description_block_id VARCHAR(255),
ADD COLUMN IF NOT EXISTS stage_tracker_block_id VARCHAR(255),
ADD COLUMN IF NOT EXISTS history_block_id VARCHAR(255);
