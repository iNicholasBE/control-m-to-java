package io.example.nightlyclose.batch;

import java.time.LocalDate;

import javax.sql.DataSource;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.JdbcPagingItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import io.example.nightlyclose.ChainProperties;
import io.example.nightlyclose.RunKey;
import io.example.nightlyclose.chain.ChainJobListener;
import io.example.nightlyclose.chain.ChainRuns;
import io.example.nightlyclose.chain.InputFiles;
import io.example.nightlyclose.tracking.StepTracker;

/**
 * Chain A as a Spring Batch job: watcher -> extract -> scoring (20 local partitions) -> join -> aggregate ->
 * report -> deliver. Every step is restartable; a restart skips COMPLETED steps and resumes the failed one.
 */
@Configuration
public class NightlyCloseJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final StepTracker tracker;
    private final ChainProperties properties;

    public NightlyCloseJobConfig(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            StepTracker tracker, ChainProperties properties) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.tracker = tracker;
        this.properties = properties;
    }

    @Bean
    public Job nightlyClose(ChainJobListener listener, Step watcher, Step extract, Step scoring, Step join,
            Step aggregate, Step report, Step deliver) {
        return new JobBuilder(RunKey.JOB_NAME, jobRepository)
                .listener(listener)
                .start(watcher).next(extract).next(scoring).next(join).next(aggregate).next(report).next(deliver)
                .build();
    }

    @Bean
    public Step watcher(WatcherTasklet tasklet) {
        return tasklet("watcher", tasklet);
    }

    @Bean
    public Step extract(FlatFileItemReader<StagedPosition> positionsReader, JdbcBatchItemWriter<StagedPosition> stagingWriter,
            ExtractInputGuard inputGuard) {
        return new StepBuilder("extract", jobRepository)
                .<StagedPosition, StagedPosition>chunk(properties.chunkSize())
                .transactionManager(transactionManager)
                .reader(positionsReader)
                .writer(stagingWriter)
                .listener(tracker)
                .listener(inputGuard)
                .build();
    }

    /** Restartable: FlatFileItemReader saves its line count in the step ExecutionContext in each chunk commit. */
    @Bean
    @StepScope
    public FlatFileItemReader<StagedPosition> positionsReader(InputFiles inputFiles,
            @Value("#{jobParameters['businessDate']}") LocalDate businessDate) {
        PositionLineMapper mapper = new PositionLineMapper();
        return new FlatFileItemReaderBuilder<StagedPosition>()
                .name("positionsReader")
                .resource(new FileSystemResource(inputFiles.positions(businessDate)))
                .linesToSkip(1)
                .skippedLinesCallback(mapper)
                .lineMapper(mapper)
                .saveState(true)
                .build();
    }

    @Bean
    @StepScope
    public JdbcBatchItemWriter<StagedPosition> stagingWriter(DataSource dataSource,
            @Value("#{jobParameters['businessDate']}") LocalDate businessDate,
            @Value("#{jobParameters['runNumber']}") Long runNumber) {
        String runId = new RunKey(businessDate, runNumber.intValue()).runId();
        return new JdbcBatchItemWriterBuilder<StagedPosition>()
                .dataSource(dataSource)
                .sql("INSERT INTO positions_staging (run_id, line_no, portfolio, raw) VALUES (?, ?, ?, ?)")
                .itemPreparedStatementSetter((item, ps) -> {
                    ps.setString(1, runId);
                    ps.setLong(2, item.lineNo());
                    ps.setString(3, item.portfolio());
                    ps.setString(4, item.raw());
                })
                .build();
    }

    @Bean
    public Step scoring(Step scoringWorker, PortfolioPartitioner portfolioPartitioner, TaskExecutor partitionExecutor) {
        return new StepBuilder("scoring", jobRepository)
                .partitioner("scoring-worker", portfolioPartitioner)
                .step(scoringWorker)
                .gridSize(properties.resources().dbConnections())
                .taskExecutor(partitionExecutor)
                .build();
    }

    @Bean
    @StepScope
    public PortfolioPartitioner portfolioPartitioner(JdbcTemplate jdbc, ChainRuns chainRuns,
            @Value("#{jobParameters['businessDate']}") LocalDate businessDate,
            @Value("#{jobParameters['runNumber']}") Long runNumber) {
        return new PortfolioPartitioner(jdbc, chainRuns, new RunKey(businessDate, runNumber.intValue()));
    }

    /**
     * Your code: the Control-M resource pool "20 DB connections". Spring Batch has no resource pools; the closest
     * honest equivalent is capping how many partition workers run at once. Per JVM only, not across a cluster.
     */
    @Bean
    public ThreadPoolTaskExecutor partitionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.resources().dbConnections());
        executor.setMaxPoolSize(properties.resources().dbConnections());
        executor.setThreadNamePrefix("scoring-");
        return executor;
    }

    @Bean
    public Step scoringWorker(JdbcPagingItemReader<StagedPosition> portfolioReader, JdbcBatchItemWriter<RiskScorer.RiskScore> scoreWriter) {
        return new StepBuilder("scoring-worker", jobRepository)
                .<StagedPosition, RiskScorer.RiskScore>chunk(properties.chunkSize())
                .transactionManager(transactionManager)
                .reader(portfolioReader)
                .processor(new RiskScorer())
                .writer(scoreWriter)
                .listener(tracker)
                .build();
    }

    /** Restartable: the paging reader remembers the last line_no committed for its portfolio. */
    @Bean
    @StepScope
    public JdbcPagingItemReader<StagedPosition> portfolioReader(DataSource dataSource,
            @Value("#{jobParameters['businessDate']}") LocalDate businessDate,
            @Value("#{jobParameters['runNumber']}") Long runNumber,
            @Value("#{stepExecutionContext['portfolio']}") String portfolio) throws Exception {
        return new JdbcPagingItemReaderBuilder<StagedPosition>()
                .name("portfolioReader")
                .dataSource(dataSource)
                .selectClause("SELECT line_no, portfolio, raw")
                .fromClause("FROM positions_staging")
                .whereClause("WHERE run_id = :runId AND portfolio = :portfolio")
                .sortKeys(java.util.Map.of("line_no", org.springframework.batch.infrastructure.item.database.Order.ASCENDING))
                .parameterValues(java.util.Map.of("runId", new RunKey(businessDate, runNumber.intValue()).runId(),
                        "portfolio", portfolio))
                .pageSize(properties.chunkSize())
                .fetchSize(properties.chunkSize())
                .rowMapper((rs, i) -> new StagedPosition(rs.getLong(1), rs.getString(2), rs.getString(3)))
                .saveState(true)
                .build();
    }

    @Bean
    @StepScope
    public JdbcBatchItemWriter<RiskScorer.RiskScore> scoreWriter(DataSource dataSource,
            @Value("#{jobParameters['businessDate']}") LocalDate businessDate,
            @Value("#{jobParameters['runNumber']}") Long runNumber) {
        String runId = new RunKey(businessDate, runNumber.intValue()).runId();
        return new JdbcBatchItemWriterBuilder<RiskScorer.RiskScore>()
                .dataSource(dataSource)
                .sql("INSERT INTO risk_scores (run_id, line_no, portfolio, score) VALUES (?, ?, ?, ?)")
                .itemPreparedStatementSetter((item, ps) -> {
                    ps.setString(1, runId);
                    ps.setLong(2, item.lineNo());
                    ps.setString(3, item.portfolio());
                    ps.setBigDecimal(4, item.score());
                })
                .build();
    }

    @Bean
    public Step join(JoinTasklet tasklet) {
        return tasklet("join", tasklet);
    }

    @Bean
    public Step aggregate(AggregateTasklet tasklet) {
        return tasklet("aggregate", tasklet);
    }

    @Bean
    public Step report(ReportTasklet tasklet) {
        return tasklet("report", tasklet);
    }

    @Bean
    public Step deliver(DeliverTasklet tasklet) {
        return tasklet("deliver", tasklet);
    }

    private Step tasklet(String name, org.springframework.batch.core.step.tasklet.Tasklet tasklet) {
        return new StepBuilder(name, jobRepository)
                .tasklet(tasklet, transactionManager)
                .listener(tracker)
                .build();
    }
}
