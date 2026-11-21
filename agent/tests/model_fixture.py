from datetime import date
from time import sleep

from fastapi import HTTPException

from app.extraction import Candidate, Conflict, Job, Reference, Result, provider
from app.main import app


class TestModel:
    def extract(self, job: Job) -> Result:
        first = job.fragments[0]
        evidence = Reference(segmentId=first.id, quote=first.excerpt)
        if "[timeout]" in first.excerpt:
            raise HTTPException(504, "MODEL_TIMEOUT")
        if "[delayed]" in first.excerpt:
            sleep(4)
        if "[conflict]" in first.excerpt:
            second = job.fragments[1]
            return Result(model="fixture", candidates=[], conflicts=[Conflict(
                reason="同一随访的复诊日期不一致",
                references=[evidence, Reference(segmentId=second.id, quote=second.excerpt)],
            )])
        if "[unsupported]" in first.excerpt:
            evidence = Reference(segmentId=first.id, quote="原文并不存在的句子")
        return Result(model="fixture", conflicts=[], candidates=[Candidate(
            kind="REVISIT", title="合成门诊复诊", instructions="请按原文所列安排复诊。",
            dueDate=date(2099, 12, 5), references=[evidence],
        )])


app.dependency_overrides[provider] = TestModel
