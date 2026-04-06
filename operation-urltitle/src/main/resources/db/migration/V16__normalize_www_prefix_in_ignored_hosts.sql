-- Remove www.-prefixed rows that duplicate an existing non-www row
DELETE FROM ignored_hosts
WHERE host_name LIKE 'www.%'
  AND SUBSTRING(host_name FROM 5) IN (SELECT host_name FROM ignored_hosts WHERE host_name NOT LIKE 'www.%');

-- Strip www. prefix from remaining rows
UPDATE ignored_hosts
SET host_name = SUBSTRING(host_name FROM 5)
WHERE host_name LIKE 'www.%';
