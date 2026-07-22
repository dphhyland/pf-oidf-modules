FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY client_attestation_sdk ./client_attestation_sdk
COPY app.py .
EXPOSE 8080
CMD ["python", "app.py"]
