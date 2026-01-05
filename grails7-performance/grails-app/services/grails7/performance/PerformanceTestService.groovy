package grails7.performance

import grails.gorm.transactions.Transactional
import groovy.time.TimeCategory

import java.util.stream.Collectors

@Transactional(readOnly = true)
class PerformanceTestService {

    private Date daysAgo(int days) {
        use(TimeCategory) {
            return new Date() - days.days
        }
    }

    private Date daysFromNow(int days) {
        use(TimeCategory) {
            return new Date() + days.days
        }
    }

    /**
     * Complex operation that performs multiple GORM queries and aggregations
     * across the entire domain graph. This is designed to stress-test GORM performance.
     */
    Map<String, Object> runComplexAnalysis() {
        def startTime = System.currentTimeMillis()
        def results = [:]

        // 1. Basic counts across all entities
        results.entityCounts = getEntityCounts()

        // 2. Complex aggregation: Company statistics with nested data
        results.companyAnalytics = getCompanyAnalytics()

        // 3. Employee analysis with skills correlation
        results.employeeAnalysis = getEmployeeAnalysis()

        // 4. Project performance metrics
        results.projectMetrics = getProjectMetrics()

        // 5. Cross-entity correlations using criteria queries
        results.crossEntityAnalysis = getCrossEntityAnalysis()

        // 6. Dynamic finders and where queries
        results.dynamicQueries = runDynamicQueries()

        // 7. HQL queries for complex joins
        results.hqlResults = runHqlQueries()

        // 8. Detached criteria queries
        results.detachedCriteriaResults = runDetachedCriteriaQueries()

        def endTime = System.currentTimeMillis()
        results.executionTimeMs = endTime - startTime

        return results
    }

    private Map<String, Long> getEntityCounts() {
        [
            companies: Company.count(),
            departments: Department.count(),
            employees: Employee.count(),
            projects: Project.count(),
            tasks: Task.count(),
            milestones: Milestone.count(),
            skills: Skill.count()
        ]
    }

    private List<Map> getCompanyAnalytics() {
        Company.list().collect { company ->
            def departments = Department.findAllByCompany(company)
            def employees = Employee.findAllByCompany(company)
            def projects = Project.createCriteria().list {
                department {
                    eq('company', company)
                }
            }
            def tasks = Task.createCriteria().list {
                project {
                    department {
                        eq('company', company)
                    }
                }
            }

            [
                companyName: company.name,
                industry: company.industry,
                departmentCount: departments.size(),
                employeeCount: employees.size(),
                activeEmployees: employees.count { it.isActive },
                totalSalaryExpense: employees.sum { it.salary ?: 0 } ?: 0,
                averageSalary: employees ? (employees.sum { it.salary ?: 0 } / employees.size()) : 0,
                projectCount: projects.size(),
                projectsByStatus: projects.groupBy { it.status }.collectEntries { k, v -> [k, v.size()] },
                totalTasks: tasks.size(),
                completedTasks: tasks.count { it.status == 'DONE' },
                averagePerformanceRating: employees ? (employees.sum { it.performanceRating ?: 0 } / employees.size()) : 0
            ]
        }
    }

    private Map<String, Object> getEmployeeAnalysis() {
        def allEmployees = Employee.list(fetch: [skills: 'eager', department: 'eager'])

        // Skill distribution
        def skillDistribution = [:]
        allEmployees.each { emp ->
            emp.skills?.each { skill ->
                skillDistribution[skill.name] = (skillDistribution[skill.name] ?: 0) + 1
            }
        }

        // Top performers by department
        def topPerformers = Department.list().collectEntries { dept ->
            def deptEmployees = Employee.findAllByDepartment(dept, [sort: 'performanceRating', order: 'desc', max: 3])
            [dept.name, deptEmployees.collect { [name: it.fullName, rating: it.performanceRating] }]
        }

        // Salary analysis by job title
        def salaryByTitle = Employee.createCriteria().list {
            projections {
                groupProperty('jobTitle')
                avg('salary')
                max('salary')
                min('salary')
                count()
            }
        }.collect { row ->
            [
                jobTitle: row[0],
                avgSalary: row[1],
                maxSalary: row[2],
                minSalary: row[3],
                count: row[4]
            ]
        }

        // Employees with most skills
        def skillLeaders = allEmployees
            .findAll { it.skills }
            .sort { -it.skills.size() }
            .take(10)
            .collect { [name: it.fullName, skillCount: it.skills.size(), skills: it.skills*.name] }

        [
            totalEmployees: allEmployees.size(),
            skillDistribution: skillDistribution,
            topPerformersByDepartment: topPerformers,
            salaryAnalysisByTitle: salaryByTitle,
            employeesWithMostSkills: skillLeaders
        ]
    }

    private Map<String, Object> getProjectMetrics() {
        def allProjects = Project.list(fetch: [tasks: 'eager', milestones: 'eager', department: 'eager'])

        // Project completion analysis
        def completionAnalysis = allProjects.stream().map((project) -> {
            def tasks = project.tasks ?: []
            def milestones = project.milestones ?: []
            def completedTasks = tasks.stream().filter({ it.status == 'DONE' }).count()
            def completedMilestones = milestones.stream().filter({ it.isCompleted }).count()

            [
                projectName: project.name,
                department: project.department?.name,
                status: project.status,
                totalTasks: tasks.size(),
                completedTasks: completedTasks,
                taskCompletionRate: tasks.size() > 0 ? (completedTasks / tasks.size() * 100).round(2) : 0,
                totalMilestones: milestones.size(),
                completedMilestones: completedMilestones,
                milestoneCompletionRate: milestones.size() > 0 ? (completedMilestones / milestones.size() * 100).round(2) : 0,
                estimatedHours: tasks.stream().collect(Collectors.summingInt (it) -> { return it.estimatedHours ?: 0
                }) ?: 0,
                actualHours   : tasks.stream().collect(Collectors.summingInt (it) -> { return it.actualHours ?: 0
                }) ?: 0,
                budget: project.budget
            ]
        }).collect(Collectors.toList())

        // Projects by status summary
        def statusSummary = allProjects.groupBy { it.status }.collectEntries { status, projects ->
            [status, [
                count: projects.size(),
                totalBudget: projects.sum { it.budget ?: 0 } ?: 0,
                avgPriority: projects.sum { it.priority ?: 0 } / projects.size()
            ]]
        }

        // Task status distribution across all projects
        def taskStatusDistribution = Task.createCriteria().list {
            projections {
                groupProperty('status')
                count()
            }
        }.collectEntries { [it[0], it[1]] }

        // Overdue tasks (due date passed but not completed)
        def today = new Date()
        def overdueTasks = Task.createCriteria().list {
            lt('dueDate', today)
            ne('status', 'DONE')
        }

        [
            projectCompletionAnalysis: completionAnalysis,
            projectStatusSummary: statusSummary,
            taskStatusDistribution: taskStatusDistribution,
            overdueTaskCount: overdueTasks.size(),
            overdueTasks: overdueTasks.take(20).collect { [title: it.title, project: it.project?.name, dueDate: it.dueDate] }
        ]
    }

    private Map<String, Object> getCrossEntityAnalysis() {
        // Find departments with highest project budgets using HQL
        def departmentBudgets = Department.executeQuery('''
            SELECT d.name, SUM(p.budget), COUNT(p.id)
            FROM Department d
            LEFT JOIN d.projects p
            GROUP BY d.id, d.name
            ORDER BY SUM(p.budget) DESC NULLS LAST
        ''').collect { [department: it[0], totalBudget: it[1] ?: 0, projectCount: it[2]] }

        // Find employees working on most projects (via tasks) using HQL
        def busyEmployees = Employee.executeQuery('''
            SELECT e.id, e.firstName, e.lastName, COUNT(DISTINCT t.project.id)
            FROM Employee e
            LEFT JOIN e.assignedTasks t
            GROUP BY e.id, e.firstName, e.lastName
            HAVING COUNT(DISTINCT t.project.id) > 0
            ORDER BY COUNT(DISTINCT t.project.id) DESC
        ''', [max: 10]).collect { [employeeId: it[0], name: "${it[1]} ${it[2]}", projectCount: it[3]] }

        // Skills most in demand (employees with high-priority tasks) using HQL
        def inDemandSkills = Skill.executeQuery('''
            SELECT s.name, COUNT(DISTINCT e.id)
            FROM Skill s
            JOIN s.employees e
            JOIN e.assignedTasks t
            WHERE t.priority > 7
            GROUP BY s.id, s.name
            ORDER BY COUNT(DISTINCT e.id) DESC
        ''', [max: 10]).collect { [skill: it[0], employeeCount: it[1]] }

        [
            departmentBudgetRanking: departmentBudgets.take(10),
            busiestEmployees: busyEmployees,
            inDemandSkills: inDemandSkills
        ]
    }

    private Map<String, Object> runDynamicQueries() {
        // Using where queries
        def highPerformers = Employee.where {
            performanceRating >= 4 && isActive == true
        }.list()

        def highPriorityProjects = Project.where {
            priority >= 8 && status in ['PLANNING', 'IN_PROGRESS']
        }.list()

        def recentHires = Employee.where {
            hireDate > daysAgo(365)
        }.list()

        // Using findAllBy dynamic finders
        def blockedTasks = Task.findAllByStatus('BLOCKED')
        def largeCompanies = Company.findAllByEmployeeCountGreaterThan(2000)
        def publicCompanies = Company.findAllByIsPublic(true)

        [
            highPerformerCount: highPerformers.size(),
            highPriorityProjectCount: highPriorityProjects.size(),
            recentHireCount: recentHires.size(),
            blockedTaskCount: blockedTasks.size(),
            largeCompanyCount: largeCompanies.size(),
            publicCompanyCount: publicCompanies.size()
        ]
    }

    private Map<String, Object> runHqlQueries() {
        // Complex HQL with multiple joins
        def projectTaskSummary = Project.executeQuery('''
            SELECT p.name, p.status, COUNT(t),
                   SUM(CASE WHEN t.status = 'DONE' THEN 1 ELSE 0 END),
                   AVG(t.estimatedHours)
            FROM Project p
            LEFT JOIN p.tasks t
            GROUP BY p.id, p.name, p.status
            ORDER BY COUNT(t) DESC
        ''').take(10).collect { row ->
            [
                project: row[0],
                status: row[1],
                taskCount: row[2],
                completedTasks: row[3],
                avgEstimatedHours: row[4]
            ]
        }

        // Employee skill summary using HQL
        def employeeSkillSummary = Employee.executeQuery('''
            SELECT e.firstName, e.lastName, e.jobTitle, COUNT(s), e.performanceRating
            FROM Employee e
            LEFT JOIN e.skills s
            GROUP BY e.id, e.firstName, e.lastName, e.jobTitle, e.performanceRating
            HAVING COUNT(s) >= 3
            ORDER BY COUNT(s) DESC, e.performanceRating DESC
        ''').take(15).collect { row ->
            [name: "${row[0]} ${row[1]}", jobTitle: row[2], skillCount: row[3], rating: row[4]]
        }

        // Department productivity (tasks completed per employee)
        def departmentProductivity = Department.executeQuery('''
            SELECT d.name, COUNT(DISTINCT e), COUNT(t),
                   SUM(CASE WHEN t.status = 'DONE' THEN 1 ELSE 0 END)
            FROM Department d
            LEFT JOIN d.employees e
            LEFT JOIN e.assignedTasks t
            GROUP BY d.id, d.name
            ORDER BY SUM(CASE WHEN t.status = 'DONE' THEN 1 ELSE 0 END) DESC
        ''').collect { row ->
            [
                department: row[0],
                employeeCount: row[1],
                totalTasks: row[2],
                completedTasks: row[3],
                productivityRate: row[1] > 0 ? ((row[3] ?: 0) / row[1]).round(2) : 0
            ]
        }

        [
            projectTaskSummary: projectTaskSummary,
            skilledEmployees: employeeSkillSummary,
            departmentProductivity: departmentProductivity
        ]
    }

    private Map<String, Object> runDetachedCriteriaQueries() {
        // Detached criteria for reusable queries
        def activeEmployeeCriteria = Employee.where {
            isActive == true
        }

        def highBudgetProjectCriteria = Project.where {
            budget > 500000
        }

        // Execute detached criteria with additional conditions
        def activeHighPerformers = activeEmployeeCriteria.where {
            performanceRating >= 4
        }.list()

        def highBudgetInProgress = highBudgetProjectCriteria.where {
            status == 'IN_PROGRESS'
        }.list()

        // Nested detached criteria
        def criticalTasks = Task.where {
            priority >= 9
            status in ['TODO', 'IN_PROGRESS', 'BLOCKED']
        }.where {
            dueDate < daysFromNow(7)
        }.list()

        [
            activeHighPerformerCount: activeHighPerformers.size(),
            activeHighPerformers: activeHighPerformers.take(10).collect { [name: it.fullName, rating: it.performanceRating] },
            highBudgetInProgressCount: highBudgetInProgress.size(),
            highBudgetProjects: highBudgetInProgress.collect { [name: it.name, budget: it.budget] },
            criticalTaskCount: criticalTasks.size(),
            criticalTasks: criticalTasks.take(10).collect { [title: it.title, priority: it.priority, dueDate: it.dueDate] }
        ]
    }

    /**
     * Runs a write-heavy operation for testing transactional performance
     */
    @Transactional
    Map<String, Object> runWriteOperations() {
        def startTime = System.currentTimeMillis()
        def operations = []

        // Update all employee performance ratings randomly
        def employees = Employee.list()
        def random = new Random()
        employees.each { emp ->
            emp.performanceRating = random.nextInt(5) + 1
            emp.save(flush: false)
        }
        Employee.withSession { it.flush() }
        operations << [operation: 'updateEmployeeRatings', count: employees.size()]

        // Update task statuses
        def todoTasks = Task.findAllByStatus('TODO')
        todoTasks.take(Math.min(50, todoTasks.size())).each { task ->
            task.status = 'IN_PROGRESS'
            task.save(flush: false)
        }
        Task.withSession { it.flush() }
        operations << [operation: 'updateTaskStatuses', count: Math.min(50, todoTasks.size())]

        // Create new tasks for random projects
        def projects = Project.list()
        5.times { i ->
            def project = projects[random.nextInt(projects.size())]
            new Task(
                title: "Performance Test Task ${System.currentTimeMillis()}-${i}",
                description: "Auto-generated task for performance testing",
                status: 'TODO',
                estimatedHours: random.nextInt(20) + 1,
                priority: random.nextInt(10) + 1,
                project: project
            ).save(flush: true, failOnError: true)
        }
        operations << [operation: 'createNewTasks', count: 5]

        def endTime = System.currentTimeMillis()

        [
            executionTimeMs: endTime - startTime,
            operations: operations,
            totalOperations: operations.sum { it.count }
        ]
    }
}
