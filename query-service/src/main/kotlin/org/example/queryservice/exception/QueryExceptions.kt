package org.example.queryservice.exception

class QueryResourceNotFoundException(resource: String, id: String) :
    RuntimeException("$resource not found - id=$id")
