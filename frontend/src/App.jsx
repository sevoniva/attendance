import { useEffect, useMemo, useState } from "react";
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Group,
  Loader,
  Paper,
  Progress,
  Stack,
  Switch,
  Table,
  Tabs,
  Text,
  TextInput,
  Title,
  Badge,
} from "@mantine/core";
import {
  IconUpload,
  IconDownload,
  IconUsers,
  IconFileDescription,
  IconTable,
  IconDatabase,
  IconClock,
  IconAlertTriangle,
  IconTrash,
  IconSettings,
  IconBook,
  IconLogout,
} from "@tabler/icons-react";
import DataTable from "./DataTable";

const MAIN_TABS = [
  { value: "summary", label: "人员汇总", icon: IconUsers },
  { value: "employee", label: "个人明细", icon: IconFileDescription },
  { value: "all", label: "全部明细", icon: IconTable },
  { value: "source", label: "原表数据", icon: IconDatabase },
  { value: "management", label: "人员管理", icon: IconSettings },
  { value: "docs", label: "计算说明", icon: IconBook },
];

function normalizeDetailRow(row) {
  return {
    ...row,
    flagsText: row.flags?.join("；") || "",
  };
}

function buildSourceRows(sheet) {
  return sheet.previewRows.map((row, index) => {
    const result = { rowNumber: index + 1 };
    for (let i = 0; i < sheet.columnCount; i += 1) {
      result[`c${i}`] = row[i] || "";
    }
    return result;
  });
}

async function safeFetch(url, options = {}, timeoutMs = 30000) {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {
      ...options,
      headers: {
        ...(options.headers || {}),
        "X-Requested-With": "XMLHttpRequest",
      },
      signal: controller.signal,
    });
    clearTimeout(timeoutId);
    if (response.status === 401 || response.status === 403) {
      window.location.href = "/login";
      return null;
    }
    // Session 过期时 API 可能被重定向到登录页，返回 HTML 而非 JSON
    if (url.startsWith("/api/") && response.ok) {
      const ct = response.headers.get("content-type") || "";
      if (!ct.includes("application/json")) {
        window.location.href = "/login";
        return null;
      }
    }
    return response;
  } catch (err) {
    clearTimeout(timeoutId);
    if (err.name === "AbortError") {
      throw new Error("请求超时，请检查网络");
    }
    throw err;
  }
}

export default function App() {
  const [files, setFiles] = useState([]);
  const [selectedFile, setSelectedFile] = useState("");
  const [report, setReport] = useState(null);
  const [selectedEmployeeId, setSelectedEmployeeId] = useState("");
  const [activeTab, setActiveTab] = useState("summary");
  const [activeSourceSheet, setActiveSourceSheet] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [uploading, setUploading] = useState(false);
  const [summarySearch, setSummarySearch] = useState("");
  const [detailSearch, setDetailSearch] = useState("");
  const [allSearch, setAllSearch] = useState("");
  const [detailOnlyExceptions, setDetailOnlyExceptions] = useState(false);
  const [employees, setEmployees] = useState([]);

  useEffect(() => {
    loadFiles();
    loadEmployees();
  }, []);

  useEffect(() => {
    if (selectedFile) {
      loadReport(selectedFile);
    }
  }, [selectedFile]);

  async function loadFiles(nextFile) {
    try {
      const response = await safeFetch("/api/files");
      if (!response) return;
      if (!response.ok) {
        throw new Error(`服务器返回 ${response.status}`);
      }
      const contentType = response.headers.get("content-type") || "";
      if (!contentType.includes("application/json")) {
        throw new Error("服务器未返回有效数据");
      }
      const data = await response.json();
      setFiles(data);
      const targetFile = nextFile || data[0] || "";
      setSelectedFile(targetFile);
      if (!targetFile) {
        setLoading(false);
      }
    } catch (fetchError) {
      setError(fetchError.message || "无法连接到考勤服务");
      setLoading(false);
    }
  }

  async function loadReport(fileName) {
    setLoading(true);
    setError("");
    try {
      const response = await safeFetch(
        `/api/report?file=${encodeURIComponent(fileName)}`
      );
      if (!response) {
        setLoading(false);
        return;
      }
      if (!response.ok) {
        throw new Error(`服务器返回 ${response.status}`);
      }
      const contentType = response.headers.get("content-type") || "";
      if (!contentType.includes("application/json")) {
        throw new Error("服务器未返回有效数据");
      }
      const data = await response.json();
      setReport(data);
      setSelectedEmployeeId(data.employees[0]?.employeeId || "");
      setActiveSourceSheet(data.sourceSheets[0]?.sheetName || "");
    } catch (fetchError) {
      setError(fetchError.message || "无法连接到考勤服务");
      setReport(null);
    } finally {
      setLoading(false);
    }
  }

  async function loadEmployees() {
    try {
      const response = await safeFetch("/api/employees");
      if (!response || !response.ok) return;
      const data = await response.json();
      setEmployees(data);
    } catch {
      // ignore
    }
  }

  async function handleUpdateRule(employeeId, dormitoryLunch, flexibleLunch, dinnerDeduct) {
    setError("");
    try {
      const response = await safeFetch(`/api/employees/${encodeURIComponent(employeeId)}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ dormitoryLunch, flexibleLunch, dinnerDeduct }),
      });
      if (!response.ok) {
        throw new Error("更新失败");
      }
      await loadEmployees();
      if (selectedFile) {
        await loadReport(selectedFile);
      }
    } catch (e) {
      setError(e.message || "更新失败");
    }
  }

  async function handleUpload(event) {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }

    const formData = new FormData();
    formData.append("file", file);
    setUploading(true);
    setError("");

    try {
      const response = await safeFetch("/api/upload", {
        method: "POST",
        body: formData,
      }, 120000);
      if (!response.ok) {
        throw new Error("上传失败");
      }
      const data = await response.json();
      setActiveTab("summary");
      setSummarySearch("");
      setDetailSearch("");
      setAllSearch("");
      setDetailOnlyExceptions(false);
      await loadFiles(data.fileName);
    } catch (uploadError) {
      setError(uploadError.message || "上传失败");
    } finally {
      setUploading(false);
      event.target.value = "";
    }
  }

  async function handleClear() {
    if (!window.confirm("确定要清空所有考勤数据吗？此操作不可恢复。")) {
      return;
    }
    setLoading(true);
    setError("");
    try {
      const response = await safeFetch("/api/clear", { method: "DELETE" });
      if (!response.ok) {
        throw new Error("清空失败");
      }
      setReport(null);
      setSelectedFile("");
      await loadFiles();
    } catch (clearError) {
      setError(clearError.message || "清空失败");
    } finally {
      setLoading(false);
    }
  }

  const selectedEmployee = useMemo(() => {
    if (!report?.employees?.length) {
      return null;
    }
    return (
      report.employees.find(
        (employee) => employee.employeeId === selectedEmployeeId
      ) || report.employees[0]
    );
  }, [report, selectedEmployeeId]);

  const summaryRows = useMemo(() => report?.summaryRows || [], [report]);

  const employeeDetailRows = useMemo(() => {
    if (!selectedEmployee) {
      return [];
    }
    return selectedEmployee.dayRows
      .map(normalizeDetailRow)
      .filter((row) => {
        const matchKeyword = detailSearch.trim()
          ? [
              row.workDate,
              row.rawText,
              row.punchesText,
              row.lunchRule,
              row.flagsText,
              row.notes,
              row.calculationBasis,
            ]
              .join(" ")
              .toLowerCase()
              .includes(detailSearch.trim().toLowerCase())
          : true;
        const matchException = detailOnlyExceptions
          ? row.overnightMerged ||
            row.incompletePunches ||
            row.flags.length > 0
          : true;
        return matchKeyword && matchException;
      });
  }, [selectedEmployee, detailSearch, detailOnlyExceptions]);

  const allDetailRows = useMemo(() => {
    if (!report) {
      return [];
    }
    return report.detailRows
      .map(normalizeDetailRow)
      .filter((row) => {
        if (!allSearch.trim()) {
          return true;
        }
        return [
          row.employeeId,
          row.name,
          row.department,
          row.workDate,
          row.rawText,
          row.punchesText,
          row.lunchRule,
          row.flagsText,
          row.notes,
          row.calculationBasis,
        ]
          .join(" ")
          .toLowerCase()
          .includes(allSearch.trim().toLowerCase());
      });
  }, [report, allSearch]);

  const currentSourceSheet = useMemo(() => {
    if (!report?.sourceSheets?.length) {
      return null;
    }
    return (
      report.sourceSheets.find(
        (sheet) => sheet.sheetName === activeSourceSheet
      ) || report.sourceSheets[0]
    );
  }, [report, activeSourceSheet]);

  const sourceRows = useMemo(
    () => (currentSourceSheet ? buildSourceRows(currentSourceSheet) : []),
    [currentSourceSheet]
  );

  const summaryColumnDefs = useMemo(
    () => [
      {
        header: "工号",
        field: "employeeId",
        pinned: true,
        width: 90,
      },
      { header: "姓名", field: "name", pinned: true, width: 110 },
      { header: "部门", field: "department", width: 130 },
      {
        header: "午休类型",
        field: "lunchLabel",
        width: 130,
      },
      {
        header: "出勤天数",
        field: "workedDays",
        width: 110,
        type: "numericColumn",
      },
      {
        header: "异常天数",
        field: "exceptionDays",
        width: 110,
        type: "numericColumn",
      },
      {
        header: "总分钟",
        field: "totalMinutes",
        width: 110,
        type: "numericColumn",
      },
      {
        header: "总小时",
        field: "totalHours",
        width: 110,
        type: "numericColumn",
      },
      {
        header: "总工作时",
        field: "totalUnits",
        width: 110,
        type: "numericColumn",
      },
    ],
    []
  );

  const detailColumnDefs = useMemo(
    () => [
      { header: "日期", field: "workDate", pinned: true, width: 110 },
      { header: "原始打卡", field: "rawText", width: 170 },
      { header: "整理后打卡", field: "punchesText", width: 180 },
      {
        header: "打卡次数",
        field: "punchCount",
        width: 100,
        type: "numericColumn",
      },
      { header: "午休规则", field: "lunchRule", width: 160 },
      {
        header: "午休扣减",
        field: "lunchDeductionMinutes",
        width: 100,
        type: "numericColumn",
      },
      {
        header: "晚餐扣减",
        field: "dinnerDeductionMinutes",
        width: 100,
        type: "numericColumn",
      },
      {
        header: "时长(分钟)",
        field: "durationMinutes",
        width: 110,
        type: "numericColumn",
      },
      {
        header: "时长(小时)",
        field: "durationHours",
        width: 110,
        type: "numericColumn",
      },
      // morningUnits / afternoonUnits 已移除
      {
        header: "工作时",
        field: "workUnits",
        width: 100,
        type: "numericColumn",
      },
      { header: "标记", field: "flagsText", width: 180 },
      { header: "备注", field: "notes", width: 180 },
      {
        header: "计算说明",
        field: "calculationBasis",
        minWidth: 320,
        flex: true,
      },
    ],
    []
  );

  const allDetailColumnDefs = useMemo(
    () => [
      {
        header: "工号",
        field: "employeeId",
        pinned: true,
        width: 90,
      },
      { header: "姓名", field: "name", pinned: true, width: 110 },
      { header: "部门", field: "department", width: 130 },
      ...detailColumnDefs,
    ],
    [detailColumnDefs]
  );

  const sourceColumnDefs = useMemo(() => {
    if (!currentSourceSheet) {
      return [];
    }
    return [
      {
        header: "#",
        field: "rowNumber",
        pinned: true,
        width: 70,
      },
      ...Array.from({ length: currentSourceSheet.columnCount }).map(
        (_, index) => ({
          header: `${index + 1}`,
          field: `c${index}`,
          minWidth: 120,
        })
      ),
    ];
  }, [currentSourceSheet]);

  return (
    <Box style={{ minHeight: "100vh", backgroundColor: "#f8fafc" }}>
      {/* Header */}
      <Paper
        component="header"
        withBorder
        radius={0}
        style={{
          position: "sticky",
          top: 0,
          zIndex: 100,
          borderBottom: "1px solid #e2e8f0",
          backgroundColor: "white",
          borderLeft: "none",
          borderRight: "none",
          borderTop: "none",
        }}
      >
        <Group
          justify="space-between"
          px="lg"
          py="sm"
          style={{ minHeight: 64 }}
          wrap="wrap"
        >
          <Box style={{ minWidth: 0 }}>
            <Text
              size="xs"
              fw={600}
              c="blue"
              style={{ letterSpacing: "0.04em" }}
            >
              Attendance Workspace
            </Text>
            <Title
              order={4}
              style={{
                fontWeight: 700,
                fontSize: 22,
                marginTop: -2,
                letterSpacing: "-0.015em",
              }}
            >
              考勤管理系统
            </Title>
            <Text size="sm" c="dimmed" style={{ marginTop: 2, fontSize: 13 }}>
              {report
                ? `统计区间 ${report.startDate} 至 ${report.endDate} · 当前文件 ${report.sourceFileName}`
                : "请先导入考勤表"}
            </Text>
          </Box>
          <Group gap="xs" wrap="wrap" justify="flex-end">
            <Button
              component="label"
              leftSection={<IconUpload size={16} />}
              color="dark"
              radius="md"
              style={{ fontWeight: 500 }}
            >
              {uploading ? "导入中..." : "导入考勤表"}
              <input
                hidden
                type="file"
                accept=".xls,.xlsx"
                onChange={handleUpload}
              />
            </Button>
            <Button
              component="a"
              variant="default"
              leftSection={<IconDownload size={16} />}
              href={
                selectedFile
                  ? `/export.xlsx?file=${encodeURIComponent(selectedFile)}`
                  : "#"
              }
              radius="md"
            >
              导出 Excel
            </Button>
            <Button
              component="a"
              variant="default"
              href={
                selectedFile
                  ? `/export.csv?file=${encodeURIComponent(selectedFile)}`
                  : "#"
              }
              radius="md"
            >
              导出 CSV
            </Button>
            <Button
              variant="default"
              color="red"
              leftSection={<IconTrash size={16} />}
              onClick={handleClear}
              radius="md"
            >
              清空数据
            </Button>
            <Button
              component="a"
              variant="default"
              leftSection={<IconLogout size={16} />}
              href="/logout"
              radius="md"
            >
              退出登录
            </Button>
          </Group>
        </Group>
        {(loading || uploading) && (
          <Progress
            value={100}
            size={2}
            color="blue"
            style={{ borderRadius: 0 }}
          />
        )}
      </Paper>

      <Box px="lg" py="lg">
        {error && (
          <Alert color="red" radius="md" mb="md" variant="light">
            {error}
          </Alert>
        )}

        {loading && !report ? (
          <Paper
            radius="lg"
            p="xl"
            style={{
              backgroundColor: "white",
              boxShadow:
                "0 1px 3px rgba(0,0,0,0.04), 0 0 0 1px rgba(0,0,0,0.03)",
              maxWidth: 520,
              margin: "48px auto",
              textAlign: "center",
            }}
          >
            <Loader size="lg" style={{ marginBottom: 16 }} />
            <Text fw={600} size="lg" mb="xs">
              加载中...
            </Text>
            <Text size="sm" c="dimmed">
              正在读取考勤数据，请稍候
            </Text>
          </Paper>
        ) : report ? (
          <>
            {/* Metrics */}
            <Box
              mb="lg"
              style={{
                display: "grid",
                gridTemplateColumns:
                  "repeat(auto-fit, minmax(180px, 1fr))",
                gap: 12,
              }}
            >
              <MetricCard
                label="人员数"
                value={report.metrics.employeeCount}
                icon={IconUsers}
                accent="#2563eb"
              />
              <MetricCard
                label="累计出勤天数"
                value={report.metrics.totalWorkedDays}
                icon={IconFileDescription}
                accent="#0ea5e9"
              />
              <MetricCard
                label="累计工时"
                value={`${report.metrics.totalHours} h`}
                icon={IconClock}
                accent="#8b5cf6"
              />
              <MetricCard
                label="异常天数"
                value={report.metrics.totalExceptionDays}
                icon={IconAlertTriangle}
                accent="#f59e0b"
              />
              <MetricCard label="当前文件" value={report.sourceFileName} mono />
            </Box>

            {/* Main Card */}
            <Paper
              radius="lg"
              p={0}
              style={{
                backgroundColor: "white",
                boxShadow:
                  "0 1px 3px rgba(0,0,0,0.04), 0 0 0 1px rgba(0,0,0,0.03)",
              }}
            >
              <Tabs value={activeTab} onChange={setActiveTab}>
                <Tabs.List
                  style={{
                    borderBottom: "1px solid #f1f5f9",
                    backgroundColor: "#fafbfc",
                    paddingLeft: 8,
                    paddingRight: 8,
                  }}
                >
                  {MAIN_TABS.map((tab) => (
                    <Tabs.Tab
                      key={tab.value}
                      value={tab.value}
                      leftSection={<tab.icon size={16} stroke={1.5} />}
                      style={{ fontWeight: 500, fontSize: 14 }}
                    >
                      {tab.label}
                    </Tabs.Tab>
                  ))}
                </Tabs.List>

                <Tabs.Panel value="summary">
                  <PanelSection
                    title="人员汇总"
                    subtitle="点击某一行可直接进入个人明细"
                    right={
                      <TextInput
                        placeholder="搜索工号 / 姓名 / 部门"
                        value={summarySearch}
                        onChange={(e) => setSummarySearch(e.target.value)}
                        style={{ minWidth: 280 }}
                        radius="md"
                        size="sm"
                      />
                    }
                  >
                    <DataTable
                      data={summaryRows}
                      columns={summaryColumnDefs}
                      searchValue={summarySearch}
                      pageSize={25}
                      onRowClick={(event) => {
                        setSelectedEmployeeId(event.data.employeeId);
                        setActiveTab("employee");
                      }}
                      getRowId={(params) => params.data.employeeId}
                      height={620}
                      defaultSortKey="totalUnits"
                      defaultSortDirection="desc"
                    />
                  </PanelSection>
                </Tabs.Panel>

                <Tabs.Panel value="employee">
                  <PanelSection
                    title={
                      selectedEmployee
                        ? `${selectedEmployee.name} 个人明细`
                        : "个人明细"
                    }
                    subtitle={
                      selectedEmployee
                        ? `工号 ${selectedEmployee.employeeId} · ${selectedEmployee.department} · 总工作时 ${selectedEmployee.totalUnits}`
                        : "请选择人员"
                    }
                    right={
                      <Group gap="xs" wrap="wrap">
                        <TextInput
                          placeholder="搜索日期 / 打卡 / 规则 / 备注"
                          value={detailSearch}
                          onChange={(e) => setDetailSearch(e.target.value)}
                          style={{ minWidth: 300 }}
                          radius="md"
                          size="sm"
                        />
                        <Checkbox
                          size="sm"
                          checked={detailOnlyExceptions}
                          onChange={(e) =>
                            setDetailOnlyExceptions(e.currentTarget.checked)
                          }
                          label="只看异常"
                          styles={{
                            label: { fontSize: 13, color: "#475569" },
                          }}
                        />
                      </Group>
                    }
                  >
                    <DataTable
                      data={employeeDetailRows}
                      columns={detailColumnDefs}
                      searchValue={detailSearch}
                      pageSize={25}
                      height={620}
                      getRowId={(params) =>
                        `${params.data.employeeId}-${params.data.workDate}-${params.data.rawText}`
                      }
                      getRowStyle={({ data }) =>
                        data.overnightMerged || data.incompletePunches
                          ? { backgroundColor: "#fef2f2" }
                          : {}
                      }
                    />
                  </PanelSection>
                </Tabs.Panel>

                <Tabs.Panel value="all">
                  <PanelSection
                    title="全部明细"
                    subtitle="当前文件内所有人员逐日记录"
                    right={
                      <TextInput
                        placeholder="搜索工号 / 姓名 / 部门 / 日期 / 备注"
                        value={allSearch}
                        onChange={(e) => setAllSearch(e.target.value)}
                        style={{ minWidth: 320 }}
                        radius="md"
                        size="sm"
                      />
                    }
                  >
                    <DataTable
                      data={allDetailRows}
                      columns={allDetailColumnDefs}
                      searchValue={allSearch}
                      pageSize={25}
                      height={640}
                      getRowId={(params) =>
                        `${params.data.employeeId}-${params.data.workDate}-${params.data.rawText}`
                      }
                      getRowStyle={({ data }) =>
                        data.overnightMerged || data.incompletePunches
                          ? { backgroundColor: "#fef2f2" }
                          : {}
                      }
                    />
                  </PanelSection>
                </Tabs.Panel>

                <Tabs.Panel value="source">
                  <Box>
                    <Box
                      px="lg"
                      pt="sm"
                      pb="xs"
                      style={{ borderBottom: "1px solid #f1f5f9" }}
                    >
                      <Text fw={600} size="sm" style={{ letterSpacing: "-0.01em" }}>
                        原表数据
                      </Text>
                      <Text size="xs" c="dimmed" style={{ marginTop: 2 }}>
                        当前导入文件的完整 sheet 数据，左右滚动查看全部列
                      </Text>
                    </Box>
                    <Tabs
                      value={activeSourceSheet}
                      onChange={setActiveSourceSheet}
                    >
                      <Tabs.List
                        style={{
                          borderBottom: "1px solid #f1f5f9",
                          paddingLeft: 8,
                          paddingRight: 8,
                        }}
                      >
                        {report.sourceSheets.map((sheet) => (
                          <Tabs.Tab
                            key={sheet.sheetName}
                            value={sheet.sheetName}
                            style={{ fontSize: 13, fontWeight: 500 }}
                          >
                            {`${sheet.sheetName} (${sheet.rowCount})`}
                          </Tabs.Tab>
                        ))}
                      </Tabs.List>
                      {report.sourceSheets.map((sheet) => (
                        <Tabs.Panel
                          key={sheet.sheetName}
                          value={sheet.sheetName}
                        >
                          <Box px="sm" py="sm">
                            {currentSourceSheet?.sheetName ===
                              sheet.sheetName && (
                              <Stack gap="sm">
                                <Group gap="xs">
                                  <Badge
                                    radius="md"
                                    size="sm"
                                    style={{
                                      fontWeight: 500,
                                      backgroundColor: "#f1f5f9",
                                      color: "#475569",
                                      border: "none",
                                    }}
                                  >
                                    {currentSourceSheet.sheetName}
                                  </Badge>
                                  <Badge
                                    radius="md"
                                    size="sm"
                                    variant="outline"
                                    style={{
                                      fontWeight: 400,
                                      borderColor: "#e2e8f0",
                                      color: "#64748b",
                                    }}
                                  >
                                    {`${currentSourceSheet.rowCount} 行`}
                                  </Badge>
                                  <Badge
                                    radius="md"
                                    size="sm"
                                    variant="outline"
                                    style={{
                                      fontWeight: 400,
                                      borderColor: "#e2e8f0",
                                      color: "#64748b",
                                    }}
                                  >
                                    {`${currentSourceSheet.columnCount} 列`}
                                  </Badge>
                                </Group>
                                <DataTable
                                  data={sourceRows}
                                  columns={sourceColumnDefs}
                                  pageSize={50}
                                  height={680}
                                  getRowId={(params) =>
                                    String(params.data.rowNumber)
                                  }
                                />
                              </Stack>
                            )}
                          </Box>
                        </Tabs.Panel>
                      ))}
                    </Tabs>
                  </Box>
                </Tabs.Panel>

                <Tabs.Panel value="management">
                  <PanelSection
                    title="人员管理"
                    subtitle="勾选对应属性后报表自动重新计算"
                  >
                    <Box style={{ overflowX: "auto" }}>
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
                            <Table.Th style={{ fontSize: 12, fontWeight: 600, color: "#475569", backgroundColor: "#f8fafc", borderBottom: "1px solid #e2e8f0", padding: "10px 14px", width: 90 }}>
                              工号
                            </Table.Th>
                            <Table.Th style={{ fontSize: 12, fontWeight: 600, color: "#475569", backgroundColor: "#f8fafc", borderBottom: "1px solid #e2e8f0", padding: "10px 14px", width: 110 }}>
                              姓名
                            </Table.Th>
                            <Table.Th style={{ fontSize: 12, fontWeight: 600, color: "#475569", backgroundColor: "#f8fafc", borderBottom: "1px solid #e2e8f0", padding: "10px 14px", width: 140 }}>
                              厂区住宿
                            </Table.Th>
                            <Table.Th style={{ fontSize: 12, fontWeight: 600, color: "#475569", backgroundColor: "#f8fafc", borderBottom: "1px solid #e2e8f0", padding: "10px 14px", width: 140 }}>
                              弹性午休
                            </Table.Th>
                            <Table.Th style={{ fontSize: 12, fontWeight: 600, color: "#475569", backgroundColor: "#f8fafc", borderBottom: "1px solid #e2e8f0", padding: "10px 14px", width: 140 }}>
                              晚餐扣除
                            </Table.Th>
                          </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                          {[...employees].sort((a, b) => parseInt(a.employeeId) - parseInt(b.employeeId)).map((emp) => (
                            <Table.Tr key={emp.employeeId}>
                              <Table.Td style={{ fontSize: 13, color: "#0f172a", borderBottom: "1px solid #f1f5f9", padding: "10px 14px" }}>
                                {emp.employeeId}
                              </Table.Td>
                              <Table.Td style={{ fontSize: 13, color: "#0f172a", borderBottom: "1px solid #f1f5f9", padding: "10px 14px" }}>
                                {emp.name}
                              </Table.Td>
                              <Table.Td style={{ fontSize: 13, color: "#0f172a", borderBottom: "1px solid #f1f5f9", padding: "10px 14px" }}>
                                <Switch
                                  size="sm"
                                  checked={!!emp.dormitoryLunch}
                                  onChange={(e) =>
                                    handleUpdateRule(emp.employeeId, e.currentTarget.checked, !!emp.flexibleLunch, !!emp.dinnerDeduct)
                                  }
                                  label={emp.dormitoryLunch ? "是" : "否"}
                                />
                              </Table.Td>
                              <Table.Td style={{ fontSize: 13, color: "#0f172a", borderBottom: "1px solid #f1f5f9", padding: "10px 14px" }}>
                                <Switch
                                  size="sm"
                                  checked={!!emp.flexibleLunch}
                                  onChange={(e) =>
                                    handleUpdateRule(emp.employeeId, !!emp.dormitoryLunch, e.currentTarget.checked, !!emp.dinnerDeduct)
                                  }
                                  label={emp.flexibleLunch ? "是" : "否"}
                                />
                              </Table.Td>
                              <Table.Td style={{ fontSize: 13, color: "#0f172a", borderBottom: "1px solid #f1f5f9", padding: "10px 14px" }}>
                                <Switch
                                  size="sm"
                                  checked={!!emp.dinnerDeduct}
                                  onChange={(e) =>
                                    handleUpdateRule(emp.employeeId, !!emp.dormitoryLunch, !!emp.flexibleLunch, e.currentTarget.checked)
                                  }
                                  label={emp.dinnerDeduct ? "是" : "否"}
                                />
                              </Table.Td>
                            </Table.Tr>
                          ))}
                        </Table.Tbody>
                      </Table>
                    </Box>
                  </PanelSection>
                </Tabs.Panel>

                <Tabs.Panel value="docs">
                  <PanelSection title="考勤计算说明" subtitle="系统计算规则文档，供核对与备忘">
                    <Box style={{ maxWidth: 840, margin: "0 auto" }}>
                      <Stack gap="lg">
                        <Box>
                          <Text fw={700} size="md" mb="xs" style={{ color: "#1e293b" }}>
                            一、打卡时间解析
                          </Text>
                          <Text size="sm" c="dimmed" style={{ lineHeight: 1.7 }}>
                            从 Excel 原始文本中提取所有 HH:mm 格式时间。连续重复的时间只保留一个。
                            例如 &quot;07:5107:51&quot; 只算一次 07:51。
                          </Text>
                        </Box>

                        <Box>
                          <Text fw={700} size="md" mb="xs" style={{ color: "#1e293b" }}>
                            二、跨天合并规则
                          </Text>
                          <Text size="sm" c="dimmed" style={{ lineHeight: 1.7 }}>
                            次日凌晨（06:00 之前）的打卡，并入前一天。条件是：当天必须有其他白天打卡（≥08:00），否则不合并。
                            合并后前一天标注&quot;跨天并单&quot;。
                          </Text>
                        </Box>

                        <Box>
                          <Text fw={700} size="md" mb="xs" style={{ color: "#1e293b" }}>
                            三、午休扣除规则
                          </Text>
                          <Text size="sm" c="dimmed" style={{ lineHeight: 1.7 }}>
                            在&quot;人员管理&quot;中可独立勾选三个属性，组合生效：
                          </Text>
                          <Box pl="md" mt="xs">
                            <Text size="sm" style={{ lineHeight: 1.8 }}>
                              <b>普通</b>：固定扣除 12:00–13:00（60分钟）<br />
                              <b>厂区住宿</b>：固定扣除 12:00–14:00（120分钟）<br />
                              <b>弹性午休</b>：默认扣除 12:00–14:00，若期间有打卡，按最早打卡时间恢复工作（减少扣除）<br />
                              <b>晚餐扣除</b>：当天打卡次数 ≥4 时，额外固定扣除 60 分钟晚餐时间
                            </Text>
                          </Box>
                        </Box>

                        <Box>
                          <Text fw={700} size="md" mb="xs" style={{ color: "#1e293b" }}>
                            四、工时计算公式
                          </Text>
                          <Box
                            p="md"
                            style={{
                              backgroundColor: "#f8fafc",
                              borderRadius: 8,
                              fontFamily: 'monospace',
                              fontSize: 13,
                              lineHeight: 1.8,
                            }}
                          >
                            <Text size="sm">
                              总时长 = 最晚打卡 − 最早打卡（分钟）<br />
                              实际工时 = 总时长 − 午休扣除 − 晚餐扣除<br />
                              工时（小时）= 实际工时 ÷ 60<br />
                              <br />
                              上午分钟 = min(午休开始, 最晚打卡) − 最早打卡<br />
                              下午分钟 = 最晚打卡 − max(午休结束, 最早打卡)
                            </Text>
                          </Box>
                        </Box>

                        <Box>
                          <Text fw={700} size="md" mb="xs" style={{ color: "#1e293b" }}>
                            五、工作时（工时制）计算
                          </Text>
                          <Text size="sm" c="dimmed" style={{ lineHeight: 1.7 }}>
                            每满 20 分钟计 0.5 工时，不满舍去。例如 608 分钟 = 30.4 个 20 分钟 = 15.0 工时。
                            公式：工时 = floor(实际工时 / 20) × 0.5
                          </Text>
                        </Box>

                        <Box>
                          <Text fw={700} size="md" mb="xs" style={{ color: "#1e293b" }}>
                            六、出勤天数规则
                          </Text>
                          <Text size="sm" c="dimmed" style={{ lineHeight: 1.7 }}>
                            当天只要有打卡记录（≥1次），即算出勤 1 天。哪怕实际工时为 0 也算出勤。
                          </Text>
                        </Box>

                        <Box>
                          <Text fw={700} size="md" mb="xs" style={{ color: "#1e293b" }}>
                            七、异常标记规则
                          </Text>
                          <Text size="sm" c="dimmed" style={{ lineHeight: 1.7 }}>
                            以下情况标淡红色背景，便于识别：
                          </Text>
                          <Box pl="md" mt="xs">
                            <Text size="sm" style={{ lineHeight: 1.8 }}>
                              • 跨天并单 — 次日凌晨打卡并入本日<br />
                              • 打卡次数异常 — 仅 1 次或 2 次打卡<br />
                              • 晚餐扣除 — 当天有晚餐扣除记录
                            </Text>
                          </Box>
                        </Box>

                        <Box>
                          <Text fw={700} size="md" mb="xs" style={{ color: "#1e293b" }}>
                            八、导出 Excel 格式
                          </Text>
                          <Text size="sm" c="dimmed" style={{ lineHeight: 1.7 }}>
                            导出包含两个 Sheet：&quot;人员汇总&quot;（按总工时降序）和&quot;每日明细&quot;（逐日逐人）。
                            表头冻结，带边框、斑马纹、自动换行。
                          </Text>
                        </Box>
                      </Stack>
                    </Box>
                  </PanelSection>
                </Tabs.Panel>
              </Tabs>
            </Paper>
          </>
        ) : (
          <Paper
            radius="lg"
            p="xl"
            style={{
              backgroundColor: "white",
              boxShadow:
                "0 1px 3px rgba(0,0,0,0.04), 0 0 0 1px rgba(0,0,0,0.03)",
              maxWidth: 520,
              margin: "48px auto",
              textAlign: "center",
            }}
          >
            <Box
              style={{
                width: 56,
                height: 56,
                borderRadius: 14,
                backgroundColor: "#f1f5f9",
                display: "grid",
                placeItems: "center",
                margin: "0 auto 16px",
                color: "#94a3b8",
              }}
            >
              <IconUpload size={28} stroke={1.5} />
            </Box>
            <Text fw={600} size="lg" mb="xs">
              还没有可用的考勤文件
            </Text>
            <Text size="sm" c="dimmed" mb="lg">
              导入一个考勤机导出的 Excel
              文件后，系统会自动分析并生成汇总、明细和原表查看页面。
            </Text>
            <Button
              component="label"
              leftSection={<IconUpload size={16} />}
              color="dark"
              radius="md"
              style={{ fontWeight: 500 }}
            >
              导入考勤表
              <input
                hidden
                type="file"
                accept=".xls,.xlsx"
                onChange={handleUpload}
              />
            </Button>
          </Paper>
        )}
      </Box>
    </Box>
  );
}

function MetricCard({ label, value, icon: Icon, mono, accent }) {
  return (
    <Paper
      radius="lg"
      p="md"
      style={{
        backgroundColor: "white",
        boxShadow:
          "0 1px 3px rgba(0,0,0,0.04), 0 0 0 1px rgba(0,0,0,0.03)",
      }}
    >
      <Group gap="sm" wrap="nowrap">
        {Icon && accent && (
          <Box
            style={{
              width: 36,
              height: 36,
              borderRadius: 10,
              display: "grid",
              placeItems: "center",
              backgroundColor: `${accent}15`,
              color: accent,
              flexShrink: 0,
            }}
          >
            <Icon size={18} stroke={1.5} />
          </Box>
        )}
        <Box style={{ minWidth: 0 }}>
          <Text size="xs" c="dimmed" fw={500}>
            {label}
          </Text>
          <Text
            size={mono ? "sm" : "xl"}
            fw={700}
            style={{
              fontFamily: mono
                ? 'ui-monospace, "SF Mono", monospace'
                : "inherit",
              letterSpacing: "-0.02em",
              marginTop: 2,
            }}
            truncate
          >
            {value}
          </Text>
        </Box>
      </Group>
    </Paper>
  );
}

function PanelSection({ title, subtitle, right, children }) {
  return (
    <Box>
      <Group
        justify="space-between"
        px="lg"
        py="sm"
        style={{
          borderBottom: "1px solid #f1f5f9",
          alignItems: "center",
        }}
        wrap="wrap"
      >
        <Box style={{ minWidth: 0 }}>
          <Text fw={600} size="sm" style={{ letterSpacing: "-0.01em" }}>
            {title}
          </Text>
          <Text size="xs" c="dimmed" style={{ marginTop: 2 }}>
            {subtitle}
          </Text>
        </Box>
        {right}
      </Group>
      <Box p="sm">{children}</Box>
    </Box>
  );
}
