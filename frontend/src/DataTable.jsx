import { useMemo, useState, useEffect } from "react";
import { Table, Pagination, Group, Box } from "@mantine/core";
import { IconChevronUp, IconChevronDown } from "@tabler/icons-react";

export default function DataTable({
  data,
  columns,
  searchValue = "",
  onRowClick,
  pageSize = 25,
  getRowId,
  getRowStyle,
  getCellStyle,
  height,
  defaultSortKey = null,
  defaultSortDirection = "asc",
}) {
  const [sortConfig, setSortConfig] = useState({
    key: defaultSortKey,
    direction: defaultSortDirection,
  });
  const [page, setPage] = useState(1);

  const filtered = useMemo(() => {
    if (!searchValue.trim()) return data;
    const term = searchValue.trim().toLowerCase();
    return data.filter((row) =>
      columns.some((col) => {
        const raw = row[col.field];
        if (raw == null) return false;
        return String(raw).toLowerCase().includes(term);
      })
    );
  }, [data, searchValue, columns]);

  const sorted = useMemo(() => {
    if (!sortConfig.key) return filtered;
    return [...filtered].sort((a, b) => {
      const aVal = a[sortConfig.key];
      const bVal = b[sortConfig.key];
      if (aVal == null && bVal == null) return 0;
      if (aVal == null) return 1;
      if (bVal == null) return -1;
      if (typeof aVal === "number" && typeof bVal === "number") {
        return sortConfig.direction === "asc" ? aVal - bVal : bVal - aVal;
      }
      const aStr = String(aVal);
      const bStr = String(bVal);
      return sortConfig.direction === "asc"
        ? aStr.localeCompare(bStr, "zh-CN")
        : bStr.localeCompare(aStr, "zh-CN");
    });
  }, [filtered, sortConfig]);

  const totalPages = Math.max(1, Math.ceil(sorted.length / pageSize));
  const paginated = useMemo(() => {
    const start = (page - 1) * pageSize;
    return sorted.slice(start, start + pageSize);
  }, [sorted, page, pageSize]);

  useEffect(() => {
    setPage(1);
  }, [searchValue, sortConfig.key, sortConfig.direction]);

  const handleSort = (field) => {
    setSortConfig((prev) => {
      if (prev.key !== field) return { key: field, direction: "asc" };
      if (prev.direction === "asc") return { key: field, direction: "desc" };
      return { key: null, direction: "asc" };
    });
  };

  const pinnedCols = columns.filter((c) => c.pinned);
  let cumulativeLeft = 0;
  const leftOffsets = {};
  for (const col of pinnedCols) {
    leftOffsets[col.field] = cumulativeLeft;
    cumulativeLeft += col.width || 120;
  }

  const wrapperStyle = height
    ? { overflow: "auto", maxHeight: height }
    : { overflowX: "auto" };

  const thBase = {
    fontSize: 12,
    fontWeight: 600,
    color: "#475569",
    letterSpacing: "0.01em",
    backgroundColor: "#f8fafc",
    borderBottom: "1px solid #e2e8f0",
    whiteSpace: "nowrap",
    userSelect: "none",
    padding: "10px 14px",
  };

  const tdBase = {
    fontSize: 13,
    color: "#0f172a",
    borderBottom: "1px solid #f1f5f9",
    padding: "10px 14px",
    whiteSpace: "nowrap",
  };

  const pinnedTh = (col) => ({
    position: "sticky",
    left: leftOffsets[col.field],
    zIndex: 3,
    backgroundColor: "#f8fafc",
  });

  const pinnedTd = (col) => ({
    position: "sticky",
    left: leftOffsets[col.field],
    zIndex: 2,
    backgroundColor: "white",
  });

  return (
    <Box>
      <Box style={wrapperStyle}>
        <Table
          striped
          highlightOnHover
          withColumnBorders={false}
          style={{ minWidth: "100%" }}
        >
          <Table.Thead
            style={{
              position: "sticky",
              top: 0,
              zIndex: 10,
            }}
          >
            <Table.Tr>
              {columns.map((col) => (
                <Table.Th
                  key={col.field}
                  onClick={() => handleSort(col.field)}
                  style={{
                    ...thBase,
                    width: col.width,
                    minWidth: col.minWidth || col.width,
                    cursor: "pointer",
                    textAlign: col.type === "numericColumn" ? "right" : "left",
                    ...(col.pinned ? pinnedTh(col) : {}),
                  }}
                >
                  <Group
                    gap={4}
                    justify={
                      col.type === "numericColumn" ? "flex-end" : "flex-start"
                    }
                  >
                    {col.header}
                    {sortConfig.key === col.field &&
                      (sortConfig.direction === "asc" ? (
                        <IconChevronUp size={14} stroke={2} />
                      ) : (
                        <IconChevronDown size={14} stroke={2} />
                      ))}
                  </Group>
                </Table.Th>
              ))}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {paginated.map((row, idx) => {
              const rowStyle = getRowStyle ? getRowStyle({ data: row }) : {};
              return (
                <Table.Tr
                  key={getRowId ? getRowId({ data: row }) : idx}
                  onClick={() => onRowClick?.({ data: row })}
                  style={{
                    cursor: onRowClick ? "pointer" : undefined,
                    ...rowStyle,
                  }}
                >
                  {columns.map((col) => {
                    let cellValue = row[col.field];
                    if (col.valueFormatter) {
                      cellValue = col.valueFormatter({ value: cellValue });
                    }
                    const cellStyle = getCellStyle
                      ? getCellStyle({ data: row, col })
                      : {};
                    return (
                      <Table.Td
                        key={col.field}
                        style={{
                          ...tdBase,
                          width: col.width,
                          minWidth: col.minWidth || col.width,
                          textAlign:
                            col.type === "numericColumn" ? "right" : "left",
                          whiteSpace: col.flex ? "pre-wrap" : "nowrap",
                          wordBreak: col.flex ? "break-word" : "normal",
                          lineHeight: col.flex ? 1.6 : "inherit",
                          ...cellStyle,
                          ...(col.pinned ? pinnedTd(col) : {}),
                        }}
                      >
                        {cellValue ?? ""}
                      </Table.Td>
                    );
                  })}
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      </Box>
      {sorted.length > pageSize && (
        <Group justify="flex-end" mt="sm" mr="sm">
          <Pagination
            value={page}
            onChange={setPage}
            total={totalPages}
            size="sm"
            withEdges={false}
            siblings={1}
          />
        </Group>
      )}
    </Box>
  );
}
