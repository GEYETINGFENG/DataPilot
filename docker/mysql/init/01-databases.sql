SET NAMES utf8mb4;
-- Application metadata, owned by the backend (Flyway migrations)
CREATE DATABASE IF NOT EXISTS datapilot DEFAULT CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'datapilot'@'%' IDENTIFIED BY 'datapilot';
GRANT ALL PRIVILEGES ON datapilot.* TO 'datapilot'@'%';

-- Sample warehouse the agent analyses. The agent's account can only read it.
CREATE DATABASE IF NOT EXISTS demo_shop DEFAULT CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'dp_reader'@'%' IDENTIFIED BY 'dp_reader';
GRANT SELECT ON demo_shop.* TO 'dp_reader'@'%';

FLUSH PRIVILEGES;
