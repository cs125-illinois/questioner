import {
  CheckstyleFailed,
  CheckstyleResults,
  CompilationFailed,
  CompiledSourceResult,
  ComplexityFailed,
  FeatureValue,
  KtlintFailed,
  KtlintResults,
  TemplatingFailed,
} from "@cs124/jeed-types"
import {
  Array as RuntypeArray,
  Boolean,
  Dictionary,
  Literal,
  Number,
  Partial,
  Record,
  Static,
  String,
  Union,
} from "runtypes"

export const Languages = Union(Literal("java"), Literal("kotlin"))
export type Languages = Static<typeof Languages>

export const QuestionPath = Record({
  path: String,
  author: String,
  version: String,
})
export type QuestionPath = Static<typeof QuestionPath>

export const QuestionDescription = QuestionPath.And(
  Record({
    name: String,
    description: String,
    packageName: String,
    starter: String,
  })
)
export type QuestionDescription = Static<typeof QuestionDescription>

export const LineCounts = Record({
  source: Number,
  comment: Number,
  blank: Number,
})
export type LineCounts = Static<typeof LineCounts>

export const Question = QuestionPath.And(
  Record({
    type: Union(Literal("SNIPPET"), Literal("METHOD"), Literal("KLASS")),
    name: String,
    packageName: String,
    languages: RuntypeArray(Languages),
    descriptions: Dictionary(String, Languages),
    complexity: Dictionary(Number, Languages),
    lineCounts: Dictionary(LineCounts, Languages),
    features: Dictionary(FeatureValue, Languages),
  })
).And(
  Partial({
    citation: Record({ source: String }).And(Partial({ link: String })),
    starters: Dictionary(String, Languages),
  })
)
export type Question = Static<typeof Question>

export const Submission = Record({
  path: String,
  language: Languages,
  contents: String,
}).And(
  Partial({
    disableLineCountLimit: Boolean,
  })
)
export type Submission = Static<typeof Submission>

export const TestResult = Record({
  name: String,
  passed: Boolean,
  type: Union(
    Literal("CONSTRUCTOR"),
    Literal("INITIALIZER"),
    Literal("METHOD"),
    Literal("STATIC_METHOD"),
    Literal("FACTORY_METHOD"),
    Literal("COPY_CONSTRUCTOR")
  ),
  runnerID: Number,
  stepCount: Number,
  methodCall: String,
}).And(
  Partial({
    message: String,
    arguments: String,
    expected: String,
    found: String,
    explanation: String,
    output: String,
    complexity: Number,
    submissionStackTrace: String,
  })
)
export type TestResult = Static<typeof TestResult>

export const TestingResult = Record({
  tests: RuntypeArray(TestResult),
  testCount: Number,
  completed: Boolean,
  passed: Boolean,
})
export type TestingResult = Static<typeof TestingResult>

export const MemoryAllocationComparison = Record({
  solution: Number,
  submission: Number,
  limit: Number,
  increase: Number,
  failed: Boolean,
})
export type MemoryAllocationComparison = Static<typeof MemoryAllocationComparison>

export const ExecutionCountComparison = Record({
  solution: Number,
  submission: Number,
  limit: Number,
  increase: Number,
  failed: Boolean,
})
export type ExecutionCountComparison = Static<typeof ExecutionCountComparison>

export const LineCoverage = Record({
  covered: Number,
  total: Number,
  missed: Number,
})
export type LineCoverage = Static<typeof LineCoverage>

export const CoverageComparison = Record({
  solution: LineCoverage,
  submission: LineCoverage,
  missed: RuntypeArray(Number),
  limit: Number,
  increase: Number,
  failed: Boolean,
})
export type CoverageComparison = Static<typeof CoverageComparison>

export const LineCountComparison = Record({
  solution: LineCounts,
  submission: LineCounts,
  limit: Number,
  allowance: Number,
  increase: Number,
  failed: Boolean,
})
export type LineCountComparison = Static<typeof LineCountComparison>

export const ComplexityComparison = Record({
  solution: Number,
  submission: Number,
  limit: Number,
  increase: Number,
  failed: Boolean,
})
export type ComplexityComparison = Static<typeof ComplexityComparison>

export const FeaturesComparison = Record({
  solution: FeatureValue,
  submission: FeatureValue,
  errors: RuntypeArray(String),
  failed: Boolean,
})
export type FeaturesComparison = Static<typeof FeaturesComparison>

export const CompletedTasks = Partial({
  // templateSubmission doesn't complete
  compileSubmission: CompiledSourceResult,
  checkstyle: CheckstyleResults,
  ktlint: KtlintResults,
  // checkCompiledSubmission doesn't complete
  complexity: ComplexityComparison,
  features: FeaturesComparison,
  lineCount: LineCountComparison,
  // execution
  // checkExecutedSubmission doesn't complete
  executionCount: ExecutionCountComparison,
  memoryAllocation: MemoryAllocationComparison,
  testing: TestingResult,
  coverage: CoverageComparison,
})
export type CompletedTasks = Static<typeof CompletedTasks>

export const FailedTasks = Partial({
  templateSubmission: TemplatingFailed,
  compileSubmission: CompilationFailed,
  checkstyle: CheckstyleFailed,
  ktlint: KtlintFailed,
  checkCompiledSubmission: String,
  complexity: ComplexityFailed,
  features: String,
  // lineCount doesn't fail
  // execution
  checkExecutedSubmission: String,
  // executionCount doesn't fail
  // memoryAllocation doesn't fail
  // testing doesn't fail
  // coverage doesn't fail
})
export type FailedTasks = Static<typeof FailedTasks>

export const Step = Union(
  Literal("templateSubmission"),
  Literal("compileSubmission"),
  Literal("checkstyle"),
  Literal("ktlint"),
  Literal("checkCompiledSubmission"),
  Literal("complexity"),
  Literal("features"),
  Literal("lineCount"),
  // execution
  Literal("checkExecutedSubmission"),
  Literal("executioncount"),
  Literal("memoryAllocation"),
  Literal("testing"),
  Literal("coverage")
)
export type Step = Static<typeof Step>

export const TestingOrder: Array<Step> = [
  "templateSubmission",
  "compileSubmission",
  "checkstyle",
  "ktlint",
  "checkCompiledSubmission",
  "complexity",
  "features",
  "lineCount",
  // execution
  "checkExecutedSubmission",
  "executioncount",
  "memoryAllocation",
  "testing",
  "coverage",
]

export const TestResults = Record({
  language: Languages,
  completedSteps: RuntypeArray(Step),
  complete: CompletedTasks,
  failedSteps: RuntypeArray(Step),
  failed: FailedTasks,
  skippedSteps: RuntypeArray(Step),
  timeout: Boolean,
  completed: Boolean,
  succeeded: Boolean,
}).And(
  Partial({
    failedLinting: Boolean,
    failureCount: Number,
  })
)
export type TestResults = Static<typeof TestResults>
