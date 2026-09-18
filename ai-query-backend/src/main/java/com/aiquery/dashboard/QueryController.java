package com.aiquery.dashboard;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import org.bson.Document;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class QueryController {
  private final QueryService queryService;

  public QueryController(QueryService queryService) {
    this.queryService = queryService;
  }

  @PostMapping("/query")
  public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest request) {
    return ResponseEntity.ok(new QueryResponse(queryService.answer(request.query())));
  }

  @GetMapping("/cards")
  public ResponseEntity<List<Document>> cards() {
    return ResponseEntity.ok(queryService.savedCards());
  }

  @PostMapping("/cards")
  public ResponseEntity<Document> saveCard(@Valid @RequestBody SaveCardRequest request) {
    return ResponseEntity.ok(queryService.saveCard(request.title(), request.query(), request.spec()));
  }

  @PutMapping("/cards/{id}")
  public ResponseEntity<Document> updateCard(@PathVariable String id, @Valid @RequestBody SaveCardRequest request) {
    return ResponseEntity.ok(queryService.updateCard(id, request.title(), request.query(), request.spec()));
  }

  @DeleteMapping("/cards/{id}")
  public ResponseEntity<Void> deleteCard(@PathVariable String id) {
    queryService.deleteCard(id);
    return ResponseEntity.noContent().build();
  }

  public record QueryRequest(@NotBlank String query) {}
  public record SaveCardRequest(@NotBlank String title, @NotBlank String query, Map<String, Object> spec) {}
  public record QueryResponse(Object renderSpec) {}
}