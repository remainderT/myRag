package buaa.rag.controller;

import buaa.rag.common.convention.result.Result;
import buaa.rag.common.convention.result.Results;
import buaa.rag.dto.EvaluationRunResponse;
import buaa.rag.service.EvalService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/eval")
public class EvaluationController {

    private final EvalService evaluationService;

    public EvaluationController(EvalService evaluationService) {
        this.evaluationService = evaluationService;
    }

    /**
     * 评测执行接口
     * POST /api/eval/run?withAnswer=true
     */
    @PostMapping("/run")
    public Result<EvaluationRunResponse> runEvaluation(
            @RequestParam(defaultValue = "true") boolean withAnswer) {
        EvaluationRunResponse response = evaluationService.runEvaluation(withAnswer);
        return Results.success(response);
    }
}
